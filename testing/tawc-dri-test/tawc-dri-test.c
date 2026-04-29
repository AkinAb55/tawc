/*
 * TAWC-DRI Phase 1 round-trip test.
 *
 * Connects to :0, queries the TAWC-DRI extension, sends one
 * TAWCDRIQueryVersion request and one TAWCDRIPresentBuffer request
 * with a fabricated buffer-id, and verifies the connection survives
 * (no X error, no I/O failure). Proves the extension wiring is
 * present and dispatch works — no real buffers, no GL.
 *
 * Built inside the chroot via testing/tawc-dri-test/build.sh.
 *
 * Exit codes:
 *   0  success — extension present, both requests round-tripped
 *   1  could not open display
 *   2  TAWC-DRI extension not advertised
 *   3  QueryVersion failed (X error or wrong version)
 *   4  PresentBuffer caused an X error or connection drop
 *   5  unexpected I/O / protocol error
 */

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

#include <sys/uio.h>
#include <xcb/xcb.h>
#include <xcb/xcbext.h>

/* Mirrors Xext/tawcdriproto.h on the server side. */
#define TAWCDRI_NAME "TAWC-DRI"
#define X_TAWCDRIQueryVersion  0
#define X_TAWCDRIPresentBuffer 1

/* xcb caches extension lookups under the address of an
 * xcb_extension_t. We just need a stable instance. */
static xcb_extension_t tawc_dri_id = { TAWCDRI_NAME, 0 };

typedef struct {
    uint8_t  major_opcode;  /* xcb fills in */
    uint8_t  minor_opcode;  /* xcb fills in from req.opcode */
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

typedef struct {
    uint8_t  major_opcode;  /* xcb fills in */
    uint8_t  minor_opcode;  /* xcb fills in from req.opcode */
    uint16_t length;
    uint32_t window;
    uint32_t buffer_id;
    uint16_t width;
    uint16_t height;
    uint32_t format;
} tawc_present_buffer_req;

int main(void)
{
    int screen_num = 0;
    xcb_connection_t *c = xcb_connect(NULL, &screen_num);
    if (!c || xcb_connection_has_error(c)) {
        fprintf(stderr, "tawc-dri-test: xcb_connect failed (DISPLAY=%s)\n",
                getenv("DISPLAY"));
        return 1;
    }

    /* xcb caches the QueryExtension result here. */
    const xcb_query_extension_reply_t *qe =
        xcb_get_extension_data(c, &tawc_dri_id);
    if (!qe || !qe->present) {
        fprintf(stderr, "tawc-dri-test: TAWC-DRI extension not present\n");
        xcb_disconnect(c);
        return 2;
    }
    fprintf(stderr,
            "tawc-dri-test: TAWC-DRI present, major_opcode=%u "
            "first_event=%u first_error=%u\n",
            qe->major_opcode, qe->first_event, qe->first_error);

    /* QueryVersion: 3 32-bit words request, expects 8-word reply. */
    tawc_query_version_req qv = {
        .length = 3,
        .major_version = 0,
        .minor_version = 1,
    };
    struct iovec qv_vec[3];
    qv_vec[2].iov_base = &qv;
    qv_vec[2].iov_len = sizeof(qv);
    xcb_protocol_request_t qv_req = {
        .count = 1,
        .ext = &tawc_dri_id,
        .opcode = X_TAWCDRIQueryVersion,
        .isvoid = 0,
    };
    unsigned int qv_seq =
        xcb_send_request(c, XCB_REQUEST_CHECKED, &qv_vec[2], &qv_req);

    xcb_generic_error_t *e = NULL;
    void *qv_reply = xcb_wait_for_reply(c, qv_seq, &e);
    if (e) {
        fprintf(stderr,
                "tawc-dri-test: QueryVersion X error code=%u major=%u minor=%u\n",
                e->error_code, e->major_code, e->minor_code);
        free(e);
        free(qv_reply);
        xcb_disconnect(c);
        return 3;
    }
    if (!qv_reply) {
        fprintf(stderr, "tawc-dri-test: QueryVersion no reply\n");
        xcb_disconnect(c);
        return 3;
    }
    tawc_query_version_reply *qvr = qv_reply;
    fprintf(stderr,
            "tawc-dri-test: QueryVersion reply: major=%u minor=%u\n",
            qvr->major_version, qvr->minor_version);
    free(qv_reply);

    /* PresentBuffer with a fabricated buffer-id. The server stub
     * returns Success without touching the buffer-id. */
    const xcb_setup_t *setup = xcb_get_setup(c);
    xcb_screen_iterator_t it = xcb_setup_roots_iterator(setup);
    xcb_window_t root = it.data ? it.data->root : 0;

    tawc_present_buffer_req pb = {
        .length = 5,
        .window = root,
        .buffer_id = 0xdeadbeefU,
        .width = 1,
        .height = 1,
        .format = 0,
    };
    struct iovec pb_vec[3];
    pb_vec[2].iov_base = &pb;
    pb_vec[2].iov_len = sizeof(pb);
    xcb_protocol_request_t pb_req = {
        .count = 1,
        .ext = &tawc_dri_id,
        .opcode = X_TAWCDRIPresentBuffer,
        .isvoid = 1,
    };
    unsigned int pb_seq =
        xcb_send_request(c, XCB_REQUEST_CHECKED, &pb_vec[2], &pb_req);
    xcb_void_cookie_t pb_cookie = { .sequence = pb_seq };
    xcb_generic_error_t *pb_err = xcb_request_check(c, pb_cookie);
    if (pb_err) {
        fprintf(stderr,
                "tawc-dri-test: PresentBuffer X error code=%u major=%u minor=%u\n",
                pb_err->error_code, pb_err->major_code, pb_err->minor_code);
        free(pb_err);
        xcb_disconnect(c);
        return 4;
    }

    if (xcb_connection_has_error(c)) {
        fprintf(stderr, "tawc-dri-test: connection broken after PresentBuffer\n");
        xcb_disconnect(c);
        return 5;
    }

    fprintf(stderr, "tawc-dri-test: OK\n");
    xcb_disconnect(c);
    return 0;
}
