// ando — run an Android command from inside the rootfs.
//
// Named like sudo, but for Android: `ando <cmd> [args…]` asks the tawc
// app process (which never had tawcroot's seccomp filter) to spawn
// <cmd> as a plain Android process, wired to this client's real
// stdin/stdout/stderr (passed via SCM_RIGHTS — tty semantics survive)
// and started in the caller's cwd (passed as an O_PATH fd; tawcroot
// translates the open, fds aren't virtualized). Protocol and design:
// notes/ando.md; broker: compositor/src/ando.rs.
//
// Wire order: fd message first (1 byte + SCM_RIGHTS[stdin, stdout,
// stderr, cwd]), then the text header, then "SIG <n>" lines out /
// one "EXIT <code>" line back.
//
// Static bionic build (tools/ando/build.sh), installed into every
// rootfs at /usr/local/bin/ando by AndoInstallProvider.

#define _GNU_SOURCE  // O_PATH

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

// Path override, mainly for tests; normal use never needs it.
#define SOCKET_ENV "TAWC_ANDO_SOCKET"
// The broker's socket as seen from inside every rootfs: the share-dir
// bind (like the wayland/kumquat sockets). tawcroot/proot translate
// the connect path through the bind; chroot resolves it natively. Keep
// the basename in sync with AppPaths.andoSocket (Kotlin).
#define SOCKET_DEFAULT "/usr/share/tawc/ando.sock"

// Exit codes for ando's own failures; the child's code is passed
// through verbatim (128+sig for signal deaths, mirroring shells).
#define EXIT_NO_BROKER 127
#define EXIT_PROTOCOL 125

static int sock_fd = -1;

static void usage(void) {
    fprintf(stderr,
            "usage: ando [-e K=V]... [--] cmd [args...]\n"
            "Run cmd as a plain Android process (no rootfs view, no fake root).\n"
            "  -e K=V   set an extra environment variable for cmd\n"
            "The Android-side env is the app process's own plus $TERM and -e extras.\n");
}

// Async-signal-safe "SIG <n>\n" writer. Forwarded signals reach the
// whole Android-side process group via the broker's kill(-pgid, n).
static void forward_signal(int sig) {
    char buf[16];
    size_t i = 0;
    buf[i++] = 'S';
    buf[i++] = 'I';
    buf[i++] = 'G';
    buf[i++] = ' ';
    if (sig >= 10) buf[i++] = (char)('0' + sig / 10);
    buf[i++] = (char)('0' + sig % 10);
    buf[i++] = '\n';
    if (sock_fd >= 0) {
        ssize_t r = write(sock_fd, buf, i);
        (void)r;
    }
}

// notes/exec-broker.md "Value encoding": \ -> \\, LF -> \n, CR -> \r.
// Returns a malloc'd string.
static char *encode_value(const char *s) {
    size_t len = strlen(s);
    char *out = malloc(len * 2 + 1);
    if (!out) return NULL;
    char *p = out;
    for (const char *c = s; *c; c++) {
        switch (*c) {
        case '\\': *p++ = '\\'; *p++ = '\\'; break;
        case '\n': *p++ = '\\'; *p++ = 'n'; break;
        case '\r': *p++ = '\\'; *p++ = 'r'; break;
        default: *p++ = *c;
        }
    }
    *p = '\0';
    return out;
}

static int write_all(int fd, const char *buf, size_t len) {
    while (len > 0) {
        ssize_t n = write(fd, buf, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        buf += n;
        len -= (size_t)n;
    }
    return 0;
}

// One header line: raw prefix (caller-controlled, e.g. "ARGV " or
// "ENV TERM="), encoded value, newline.
static int send_line(int fd, const char *prefix, const char *value) {
    char *enc = encode_value(value);
    if (!enc) return -1;
    int rc = write_all(fd, prefix, strlen(prefix));
    if (rc == 0) rc = write_all(fd, enc, strlen(enc));
    if (rc == 0) rc = write_all(fd, "\n", 1);
    free(enc);
    return rc;
}

// "ENV K=V": only the value half is encoded (keys never carry control
// chars — same rule as the exec broker).
static int send_env(int fd, const char *kv) {
    const char *eq = strchr(kv, '=');  // presence validated by caller
    if (write_all(fd, "ENV ", 4) != 0) return -1;
    if (write_all(fd, kv, (size_t)(eq - kv + 1)) != 0) return -1;
    char *enc = encode_value(eq + 1);
    if (!enc) return -1;
    int rc = write_all(fd, enc, strlen(enc));
    if (rc == 0) rc = write_all(fd, "\n", 1);
    free(enc);
    return rc;
}

static int connect_broker(void) {
    const char *name = getenv(SOCKET_ENV);
    if (!name || !*name) name = SOCKET_DEFAULT;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    size_t namelen = strlen(name);
    if (namelen + 1 > sizeof(addr.sun_path)) {
        fprintf(stderr, "ando: socket path too long: %s\n", name);
        return -1;
    }
    memcpy(addr.sun_path, name, namelen + 1);

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        perror("ando: socket");
        return -1;
    }
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        fprintf(stderr, "ando: broker not running at %s (%s) — is the tawc app alive?\n",
                name, strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

// A closed std fd would make the SCM_RIGHTS sendmsg fail with EBADF;
// substitute /dev/null so `ando cmd 0<&-` still runs.
static int usable_fd(int fd) {
    if (fcntl(fd, F_GETFD) != -1) return fd;
    int nul = open("/dev/null", fd == 0 ? O_RDONLY : O_WRONLY);
    return nul >= 0 ? nul : fd;
}

static int send_fds(int sock, int cwd_fd) {
    char byte = 'F';
    struct iovec iov = { .iov_base = &byte, .iov_len = 1 };
    int fds[4] = { usable_fd(0), usable_fd(1), usable_fd(2), cwd_fd };
    union {
        char buf[CMSG_SPACE(sizeof(fds))];
        struct cmsghdr align;
    } u;
    memset(&u, 0, sizeof(u));
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = u.buf;
    msg.msg_controllen = sizeof(u.buf);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(fds));
    memcpy(CMSG_DATA(cmsg), fds, sizeof(fds));
    ssize_t n;
    do {
        n = sendmsg(sock, &msg, 0);
    } while (n < 0 && errno == EINTR);
    if (n != 1) {
        perror("ando: sendmsg");
        return -1;
    }
    return 0;
}

// Block until the broker's "EXIT <code>" line; return the code.
static int await_exit(int sock) {
    char buf[256];
    size_t have = 0;
    for (;;) {
        ssize_t n = read(sock, buf + have, sizeof(buf) - have - 1);
        if (n < 0) {
            if (errno == EINTR) continue;
            perror("ando: read");
            return EXIT_PROTOCOL;
        }
        if (n == 0) {
            fprintf(stderr, "ando: broker closed connection without exit status\n");
            return EXIT_PROTOCOL;
        }
        have += (size_t)n;
        buf[have] = '\0';
        char *nl;
        char *start = buf;
        while ((nl = strchr(start, '\n'))) {
            *nl = '\0';
            if (strncmp(start, "EXIT ", 5) == 0) {
                return (int)strtol(start + 5, NULL, 10);
            }
            start = nl + 1;
        }
        size_t rest = have - (size_t)(start - buf);
        memmove(buf, start, rest);
        have = rest;
        if (have >= sizeof(buf) - 1) have = 0;  // garbage line; drop
    }
}

int main(int argc, char **argv) {
    // A broker that dies mid-session must surface as an error message,
    // not a silent SIGPIPE death.
    signal(SIGPIPE, SIG_IGN);

    int argi = 1;
    const char *env_extras[256];
    size_t n_env = 0;

    for (; argi < argc; argi++) {
        if (strcmp(argv[argi], "--") == 0) {
            argi++;
            break;
        }
        if (strcmp(argv[argi], "-e") == 0) {
            if (argi + 1 >= argc || !strchr(argv[argi + 1], '=')) {
                fprintf(stderr, "ando: -e needs K=V\n");
                return EXIT_PROTOCOL;
            }
            if (n_env >= sizeof(env_extras) / sizeof(env_extras[0])) {
                fprintf(stderr, "ando: too many -e\n");
                return EXIT_PROTOCOL;
            }
            env_extras[n_env++] = argv[++argi];
            continue;
        }
        if (strcmp(argv[argi], "-h") == 0 || strcmp(argv[argi], "--help") == 0) {
            usage();
            return 0;
        }
        if (argv[argi][0] == '-' && argv[argi][1] != '\0') {
            fprintf(stderr, "ando: unknown option %s\n", argv[argi]);
            usage();
            return EXIT_PROTOCOL;
        }
        break;
    }
    if (argi >= argc) {
        usage();
        return EXIT_PROTOCOL;
    }

    int cwd_fd = open(".", O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (cwd_fd < 0) {
        // Deleted cwd etc. — hand over / (the broker child only falls
        // back to the app process's cwd if fchdir itself fails).
        cwd_fd = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC);
        if (cwd_fd < 0) {
            perror("ando: open cwd");
            return EXIT_PROTOCOL;
        }
    }

    sock_fd = connect_broker();
    if (sock_fd < 0) return EXIT_NO_BROKER;

    if (send_fds(sock_fd, cwd_fd) != 0) return EXIT_PROTOCOL;
    close(cwd_fd);

    if (write_all(sock_fd, "TAWCANDO 1\n", 11) != 0) goto wfail;
    for (int i = argi; i < argc; i++) {
        if (send_line(sock_fd, "ARGV ", argv[i]) != 0) goto wfail;
    }
    const char *term = getenv("TERM");
    if (term && *term) {
        if (send_line(sock_fd, "ENV TERM=", term) != 0) goto wfail;
    }
    for (size_t i = 0; i < n_env; i++) {
        if (send_env(sock_fd, env_extras[i]) != 0) goto wfail;
    }
    if (write_all(sock_fd, "\n", 1) != 0) goto wfail;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = forward_signal;
    sa.sa_flags = SA_RESTART;  // keep the EXIT read going across signals
    sigemptyset(&sa.sa_mask);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    sigaction(SIGQUIT, &sa, NULL);

    return await_exit(sock_fd);

wfail:
    fprintf(stderr, "ando: writing request failed (%s)\n", strerror(errno));
    return EXIT_PROTOCOL;
}
