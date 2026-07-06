/*
 * TAWC-DRI Phase 2 round-trip + animated-loop test.
 *
 * Connects to :0, queries the TAWC-DRI extension, creates a small
 * visible X window so Xwayland binds it to a wl_surface, allocates
 * one or two real AHardwareBuffers on the chroot side via libhybris-
 * loaded libnativewindow, CPU-fills with a green→yellow gradient,
 * and ships them via TAWCDRIPresentBuffer (FD passing). The X server
 * rebuilds each AHB via AHardwareBuffer_createFromHandle, ships it
 * through android_wlegl, and attaches the wl_buffer to the X11
 * window's wl_surface.
 *
 * libnativewindow.so is a bionic-only library; we load it through
 * libhybris-common's hybris_dlopen so the chroot's glibc loader
 * doesn't reject it.
 *
 * Modes (mutually exclusive — TAWC_DRI_LOOP_FRAMES wins if both are set):
 *   - default                           : single present, exit
 *   - TAWC_DRI_HOLD_SECS=N (default 1)  : present at 5 Hz for N seconds
 *   - TAWC_DRI_LOOP_FRAMES=N            : double-buffered animated
 *                                          loop for N frames at ~60fps
 *
 * Built by tests/apps/Makefile for integration tests.
 *
 * Exit codes:
 *   0  success — all configured presents ran clean
 *   1  could not open display
 *   2  TAWC-DRI extension not advertised
 *   3  QueryVersion failed
 *   4  PresentBuffer caused an X error or connection drop
 *   5  unexpected I/O / protocol error
 *   6  failed to load libnativewindow.so or resolve AHB symbols
 *   7  AHB allocate / lock / getNativeHandle failed
 *   8  failed to map test X11 window
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <dlfcn.h>

#include <sys/uio.h>
#include <xcb/xcb.h>
#include <xcb/xcbext.h>

/* Mirrors Xext/tawcdriproto.h. */
#define TAWCDRI_NAME "TAWC-DRI"
#define X_TAWCDRIQueryVersion  0
#define X_TAWCDRIPresentBuffer 1

#define TEST_W 320
#define TEST_H 240

/* AHB API mirrored from <android/hardware_buffer.h> so we don't need
 * the NDK headers in the chroot. dlopen + dlsym at runtime. */
#define AHB_FORMAT_R8G8B8A8_UNORM       1
#define AHB_USAGE_CPU_READ_OFTEN        0x3ULL
#define AHB_USAGE_CPU_WRITE_OFTEN       0x30ULL
#define AHB_USAGE_GPU_SAMPLED_IMAGE     0x100ULL

struct ahb_desc {
    uint32_t width, height, layers, format;
    uint64_t usage;
    uint32_t stride, rfu0;
    uint64_t rfu1;
};

struct native_handle {
    int version;
    int numFds;
    int numInts;
    int data[];
};

struct AHardwareBuffer;

typedef int  (*pfn_alloc_t)(const struct ahb_desc *, struct AHardwareBuffer **);
typedef void (*pfn_release_t)(struct AHardwareBuffer *);
typedef void (*pfn_describe_t)(struct AHardwareBuffer *, struct ahb_desc *);
typedef int  (*pfn_lock_t)(struct AHardwareBuffer *, uint64_t, int32_t,
                           const void *, void **);
typedef int  (*pfn_unlock_t)(struct AHardwareBuffer *, int32_t *);
typedef const struct native_handle *
             (*pfn_get_handle_t)(const struct AHardwareBuffer *);

static pfn_alloc_t       p_alloc;
static pfn_release_t     p_release;
static pfn_describe_t    p_describe;
static pfn_lock_t        p_lock;
static pfn_unlock_t      p_unlock;
static pfn_get_handle_t  p_get_handle;

/* Forward-declared from <hybris/common/dlfcn.h>. Headers aren't
 * installed in the chroot; the implementation lives in
 * libhybris-common.so which is. hybris_dlopen runs the bionic linker
 * so /system/lib64/libnativewindow.so loads with its bionic
 * libc/libcutils/libui/etc. */
extern void *hybris_dlopen(const char *filename, int flag);
extern void *hybris_dlsym(void *handle, const char *symbol);

static int load_libnativewindow(void)
{
    void *lib = hybris_dlopen("libnativewindow.so", RTLD_NOW);
    if (!lib) {
        fprintf(stderr, "tawc-dri-test: hybris_dlopen(libnativewindow.so) failed\n");
        return -1;
    }
    p_alloc      = (pfn_alloc_t)      hybris_dlsym(lib, "AHardwareBuffer_allocate");
    p_release    = (pfn_release_t)    hybris_dlsym(lib, "AHardwareBuffer_release");
    p_describe   = (pfn_describe_t)   hybris_dlsym(lib, "AHardwareBuffer_describe");
    p_lock       = (pfn_lock_t)       hybris_dlsym(lib, "AHardwareBuffer_lock");
    p_unlock     = (pfn_unlock_t)     hybris_dlsym(lib, "AHardwareBuffer_unlock");
    p_get_handle = (pfn_get_handle_t) hybris_dlsym(lib, "AHardwareBuffer_getNativeHandle");
    if (!p_alloc || !p_release || !p_describe ||
        !p_lock  || !p_unlock  || !p_get_handle) {
        fprintf(stderr, "tawc-dri-test: hybris_dlsym AHardwareBuffer_* failed\n");
        return -1;
    }
    return 0;
}

static xcb_extension_t tawc_dri_id = { TAWCDRI_NAME, 0 };

typedef struct {
    uint8_t  major_opcode;
    uint8_t  minor_opcode;
    uint16_t length;
    uint32_t major_version;
    uint32_t minor_version;
} tawc_query_version_req;

typedef struct {
    uint8_t  response_type;
    uint8_t  pad0;
    uint16_t sequence;
    uint32_t length;
    uint32_t major_version;
    uint32_t minor_version;
    uint32_t pad1[5];
} tawc_query_version_reply;

/* Mirrors xTAWCDRIPresentBufferReq (v0.3). xcb fills major_opcode +
 * minor_opcode for us. `serial` is echoed in TAWCDRIBufferRelease for
 * clients that SelectInput; we don't, so 0 is fine. Against a pre-0.3
 * server the trailing serial is dropped (36-byte v0.2 shape) — see
 * server_v03 in main(). */
typedef struct __attribute__((packed)) {
    uint8_t  major_opcode;
    uint8_t  minor_opcode;
    uint16_t length;
    uint32_t window;
    uint16_t num_fds;
    uint16_t num_ints;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t format;
    uint32_t usage_lo;
    uint32_t usage_hi;
    uint32_t serial;
} tawc_present_buffer_req;

/* Set from the QueryVersion reply before any buffer is prepared. */
static int server_v03;

/* One AHB-backed buffer ready to ship: AHB + cached
 * native_handle pointer (owned by the AHB) + a precomputed
 * tawc_present_buffer_req-with-inline-ints body. The combined body
 * doesn't change frame to frame, so we build it once. */
struct buffer_state {
    struct AHardwareBuffer *ahb;
    const struct native_handle *nh;
    int num_fds;
    int num_ints;
    char *combined_req;
    size_t combined_sz;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
};

static int
prepare_buffer(xcb_window_t win, struct buffer_state *out)
{
    struct ahb_desc desc = {
        .width = TEST_W, .height = TEST_H, .layers = 1,
        .format = AHB_FORMAT_R8G8B8A8_UNORM,
        .usage = AHB_USAGE_CPU_READ_OFTEN | AHB_USAGE_CPU_WRITE_OFTEN |
                 AHB_USAGE_GPU_SAMPLED_IMAGE,
    };
    struct ahb_desc actual = {0};

    memset(out, 0, sizeof(*out));

    if (p_alloc(&desc, &out->ahb) != 0 || !out->ahb) {
        fprintf(stderr, "tawc-dri-test: AHardwareBuffer_allocate failed\n");
        return -1;
    }
    p_describe(out->ahb, &actual);
    out->width  = actual.width;
    out->height = actual.height;
    out->stride = actual.stride;

    out->nh = p_get_handle(out->ahb);
    if (!out->nh) {
        fprintf(stderr, "tawc-dri-test: AHardwareBuffer_getNativeHandle failed\n");
        return -1;
    }
    out->num_fds  = out->nh->numFds;
    out->num_ints = out->nh->numInts;

    size_t hdr_sz  = server_v03
        ? sizeof(tawc_present_buffer_req)
        : sizeof(tawc_present_buffer_req) - sizeof(uint32_t);
    size_t ints_sz = (size_t)out->num_ints * sizeof(int32_t);
    out->combined_sz = hdr_sz + ints_sz;
    out->combined_req = malloc(out->combined_sz);
    if (!out->combined_req)
        return -1;

    tawc_present_buffer_req hdr = {
        .length   = (uint16_t)((out->combined_sz + 3) / 4),
        .window   = win,
        .num_fds  = (uint16_t)out->num_fds,
        .num_ints = (uint16_t)out->num_ints,
        .width    = actual.width,
        .height   = actual.height,
        .stride   = actual.stride,
        .format   = actual.format,
        .usage_lo = (uint32_t)(actual.usage & 0xffffffffULL),
        .usage_hi = (uint32_t)(actual.usage >> 32),
    };
    memcpy(out->combined_req, &hdr, hdr_sz);
    if (ints_sz > 0)
        memcpy(out->combined_req + hdr_sz,
               out->nh->data + out->num_fds, ints_sz);
    return 0;
}

static void
release_buffer(struct buffer_state *s)
{
    if (!s) return;
    free(s->combined_req);
    if (s->ahb) p_release(s->ahb);
    s->ahb = NULL;
    s->nh = NULL;
    s->combined_req = NULL;
}

/* Paint a green→yellow gradient with a per-frame phase offset.
 * frame=0 matches step 2's static pattern; frame > 0 sweeps the
 * "yellow boundary" across the surface, so the loop test is visibly
 * animated and any stuck-frame regression on the compositor side
 * shows up as a frozen image. */
static int
paint_gradient(struct buffer_state *s, int frame)
{
    void *cpu = NULL;
    if (p_lock(s->ahb, AHB_USAGE_CPU_WRITE_OFTEN, -1, NULL, &cpu) != 0
        || !cpu)
        return -1;
    uint32_t *px = cpu;
    /* Sweep phase across the buffer width every 60 frames so 60fps =
     * one cycle/sec. */
    int phase = (frame * (int)s->width / 60) % (int)s->width;
    for (uint32_t y = 0; y < s->height; y++) {
        for (uint32_t x = 0; x < s->width; x++) {
            uint32_t xx = (x + (uint32_t)phase) % s->width;
            uint8_t r = (uint8_t)((xx * 255) / (s->width - 1));
            uint8_t g = 0xff;
            uint8_t b = 0x10;
            uint8_t a = 0xff;
            px[y * s->stride + x] =
                ((uint32_t)a << 24) | ((uint32_t)b << 16) |
                ((uint32_t)g << 8)  | ((uint32_t)r);
        }
    }
    p_unlock(s->ahb, NULL);
    return 0;
}

/* Send one TAWCDRIPresentBuffer. xcb dups the fds and closes them
 * after sending (we keep the AHB's originals via getNativeHandle).
 * Returns 0 on success or one of the exit codes on failure. */
static int
present(xcb_connection_t *c, struct buffer_state *s, int frame)
{
    /* xcb mutates the iovec across calls; reset every time. */
    struct iovec ivec[3];
    ivec[2].iov_base = s->combined_req;
    ivec[2].iov_len  = s->combined_sz;

    int *xfds = NULL;
    if (s->num_fds > 0) {
        xfds = malloc(s->num_fds * sizeof(int));
        if (!xfds) return 5;
        for (int i = 0; i < s->num_fds; i++) {
            xfds[i] = dup(s->nh->data[i]);
            if (xfds[i] < 0) {
                while (--i >= 0) close(xfds[i]);
                free(xfds);
                return 5;
            }
        }
    }

    xcb_protocol_request_t pb_req = {
        .count = 1, .ext = &tawc_dri_id,
        .opcode = X_TAWCDRIPresentBuffer, .isvoid = 1,
    };
    unsigned int seq = xcb_send_request_with_fds(
        c, XCB_REQUEST_CHECKED, &ivec[2], &pb_req, s->num_fds, xfds);
    free(xfds);

    xcb_void_cookie_t cookie = { .sequence = seq };
    xcb_generic_error_t *err = xcb_request_check(c, cookie);
    if (err) {
        fprintf(stderr,
                "tawc-dri-test: PresentBuffer #%d X error code=%u\n",
                frame, err->error_code);
        free(err);
        return 4;
    }
    if (xcb_connection_has_error(c)) {
        fprintf(stderr,
                "tawc-dri-test: connection broken after PresentBuffer #%d\n",
                frame);
        return 5;
    }
    return 0;
}

static double
now_secs(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
}

int main(void)
{
    int screen_num = 0;
    xcb_connection_t *c = xcb_connect(NULL, &screen_num);
    if (!c || xcb_connection_has_error(c)) {
        fprintf(stderr, "tawc-dri-test: xcb_connect failed (DISPLAY=%s)\n",
                getenv("DISPLAY"));
        return 1;
    }

    const xcb_query_extension_reply_t *qe =
        xcb_get_extension_data(c, &tawc_dri_id);
    if (!qe || !qe->present) {
        fprintf(stderr, "tawc-dri-test: TAWC-DRI extension not present\n");
        xcb_disconnect(c);
        return 2;
    }
    fprintf(stderr,
            "tawc-dri-test: TAWC-DRI present, major_opcode=%u\n",
            qe->major_opcode);

    /* QueryVersion. */
    tawc_query_version_req qv = {
        .length = 3, .major_version = 0, .minor_version = 3,
    };
    struct iovec qv_vec[3];
    qv_vec[2].iov_base = &qv;
    qv_vec[2].iov_len  = sizeof(qv);
    xcb_protocol_request_t qv_req = {
        .count = 1, .ext = &tawc_dri_id,
        .opcode = X_TAWCDRIQueryVersion, .isvoid = 0,
    };
    unsigned int qv_seq =
        xcb_send_request(c, XCB_REQUEST_CHECKED, &qv_vec[2], &qv_req);
    xcb_generic_error_t *e = NULL;
    void *qv_reply = xcb_wait_for_reply(c, qv_seq, &e);
    if (e) {
        fprintf(stderr, "tawc-dri-test: QueryVersion X error code=%u\n",
                e->error_code);
        free(e); free(qv_reply); xcb_disconnect(c);
        return 3;
    }
    if (!qv_reply) {
        xcb_disconnect(c); return 3;
    }
    tawc_query_version_reply *qvr = qv_reply;
    fprintf(stderr,
            "tawc-dri-test: TAWC-DRI version %u.%u\n",
            qvr->major_version, qvr->minor_version);
    server_v03 = qvr->major_version == 0 && qvr->minor_version >= 3;
    free(qv_reply);

    /* Create + map an X window. */
    const xcb_setup_t *setup = xcb_get_setup(c);
    xcb_screen_iterator_t it = xcb_setup_roots_iterator(setup);
    xcb_screen_t *screen = it.data;
    if (!screen) {
        fprintf(stderr, "tawc-dri-test: no screen\n");
        xcb_disconnect(c); return 5;
    }
    xcb_window_t win = xcb_generate_id(c);
    uint32_t mask = XCB_CW_BACK_PIXEL | XCB_CW_EVENT_MASK;
    uint32_t values[2] = {
        screen->black_pixel,
        XCB_EVENT_MASK_EXPOSURE | XCB_EVENT_MASK_STRUCTURE_NOTIFY,
    };
    xcb_create_window(c, XCB_COPY_FROM_PARENT, win, screen->root,
                      0, 0, TEST_W, TEST_H, 0,
                      XCB_WINDOW_CLASS_INPUT_OUTPUT,
                      screen->root_visual, mask, values);
    xcb_map_window(c, win);
    xcb_flush(c);

    xcb_generic_error_t *gge = NULL;
    xcb_get_geometry_reply_t *gg_r =
        xcb_get_geometry_reply(c, xcb_get_geometry(c, win), &gge);
    if (!gg_r) {
        fprintf(stderr, "tawc-dri-test: GetGeometry failed\n");
        if (gge) free(gge);
        xcb_disconnect(c);
        return 8;
    }
    free(gg_r);

    if (load_libnativewindow() != 0) {
        xcb_disconnect(c); return 6;
    }

    /* Mode selection. */
    int loop_frames = 0;
    int hold_secs = 1;
    {
        const char *lf = getenv("TAWC_DRI_LOOP_FRAMES");
        if (lf) loop_frames = atoi(lf);
        const char *hs = getenv("TAWC_DRI_HOLD_SECS");
        if (hs) hold_secs = atoi(hs);
    }

    int rc = 0;

    if (loop_frames > 0) {
        /* Animated double-buffered loop at ~60fps. The double-buffer
         * exercises the X server's ability to handle alternating AHBs
         * (each TAWCDRIPresentBuffer creates a fresh server-side AHB
         * import — a single-buffered loop wouldn't show buffer-handle
         * lifecycle issues), and the per-frame repaint guarantees the
         * compositor isn't just drawing the same texture forever. */
        struct buffer_state buf[2];
        if (prepare_buffer(win, &buf[0]) != 0
            || prepare_buffer(win, &buf[1]) != 0) {
            release_buffer(&buf[0]);
            release_buffer(&buf[1]);
            xcb_disconnect(c); return 7;
        }
        fprintf(stderr,
                "tawc-dri-test: loop mode %d frames "
                "(2x %ux%u stride=%u numFds=%d numInts=%d)\n",
                loop_frames,
                buf[0].width, buf[0].height, buf[0].stride,
                buf[0].num_fds, buf[0].num_ints);

        const double frame_dt = 1.0 / 60.0;
        double start = now_secs();
        double next_deadline = start;
        for (int f = 0; f < loop_frames && rc == 0; f++) {
            struct buffer_state *s = &buf[f & 1];
            if (paint_gradient(s, f) != 0) { rc = 7; break; }
            if ((rc = present(c, s, f)) != 0) break;
            xcb_flush(c);
            next_deadline += frame_dt;
            double now = now_secs();
            if (next_deadline > now)
                usleep((useconds_t)((next_deadline - now) * 1e6));
        }
        double elapsed = now_secs() - start;
        if (rc == 0) {
            fprintf(stderr,
                    "tawc-dri-test: loop ran %d frames in %.2fs (%.1f fps)\n",
                    loop_frames, elapsed,
                    elapsed > 0 ? loop_frames / elapsed : 0.0);
        }
        release_buffer(&buf[0]);
        release_buffer(&buf[1]);
    } else {
        /* Single-buffer hold mode. */
        struct buffer_state buf;
        if (prepare_buffer(win, &buf) != 0) {
            release_buffer(&buf);
            xcb_disconnect(c); return 7;
        }
        if (paint_gradient(&buf, 0) != 0) {
            release_buffer(&buf);
            xcb_disconnect(c); return 7;
        }
        fprintf(stderr,
                "tawc-dri-test: AHB %ux%u stride=%u numFds=%d numInts=%d\n",
                buf.width, buf.height, buf.stride, buf.num_fds, buf.num_ints);

        /* The X11 toplevel triggers an Activity spawn on the
         * compositor side; that Activity's SurfaceView only becomes a
         * render target after Android's task launch finishes. If we
         * present once immediately after map_window, the AHB lands on
         * a wl_surface that has no render output yet, and the
         * gradient never reaches the screen. Re-present at 5 Hz so
         * the next render after the SurfaceView attaches sees a fresh
         * commit. */
        int n_presents = hold_secs > 0 ? hold_secs * 5 : 1;
        for (int p = 0; p < n_presents; p++) {
            if ((rc = present(c, &buf, p)) != 0) break;
            xcb_flush(c);
            usleep(200 * 1000);
        }
        release_buffer(&buf);
    }

    if (rc != 0) {
        xcb_disconnect(c); return rc;
    }

    fprintf(stderr, "tawc-dri-test: OK\n");
    xcb_disconnect(c);
    return 0;
}
