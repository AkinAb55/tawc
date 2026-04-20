/*
 * gtk4-debug-app: A GTK4 debug/test app for the tawc compositor.
 *
 * Sister binary to gtk3-debug-app, ported to GTK4. Same subcommand CLI and
 * output protocol (TAWC_DEBUG: prefix parsed by the Rust harness), so tests
 * can target GTK4 (always hardware-buffered via android_wlegl) and GTK3
 * (SHM vs AHB selectable via GDK_GL) through one harness.
 *
 * Usage: gtk4-debug-app <command>
 *
 * Commands:
 *   text-input   Open a text view for testing text input
 */

#include <gtk/gtk.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* --- Output protocol ---------------------------------------------------- */

static void debug_emit(const char *tag, const char *value)
{
    if (value && value[0])
        printf("TAWC_DEBUG:%s:%s\n", tag, value);
    else
        printf("TAWC_DEBUG:%s\n", tag);
    fflush(stdout);
}

/* --- text-input command ------------------------------------------------- */

static GMainLoop *main_loop;
static GtkWidget *text_view;

static void on_window_destroy(GtkWidget *window, gpointer user_data)
{
    (void)window;
    (void)user_data;
    if (main_loop)
        g_main_loop_quit(main_loop);
}

static void on_text_buffer_changed(GtkTextBuffer *buffer, gpointer user_data)
{
    (void)user_data;
    GtkTextIter start, end;
    gtk_text_buffer_get_bounds(buffer, &start, &end);
    char *text = gtk_text_buffer_get_text(buffer, &start, &end, FALSE);
    debug_emit("TEXT_CHANGED", text);
    g_free(text);
}

static void on_preedit_changed(GtkTextView *tv, const char *preedit, gpointer user_data)
{
    (void)tv;
    (void)user_data;
    debug_emit("PREEDIT", preedit);
}

static void on_mark_set(GtkTextBuffer *buffer, GtkTextIter *location,
                        GtkTextMark *mark, gpointer user_data)
{
    (void)user_data;
    if (mark != gtk_text_buffer_get_insert(buffer))
        return;
    int offset = gtk_text_iter_get_offset(location);
    char buf[32];
    snprintf(buf, sizeof(buf), "%d", offset);
    debug_emit("CURSOR_POS", buf);
}

static gboolean emit_ready(gpointer user_data)
{
    (void)user_data;
    debug_emit("READY", NULL);
    return G_SOURCE_REMOVE;
}

/* The GTK4 "map" signal fires when the widget's surface is mapped. Defer the
 * READY emission briefly so the IM context has a chance to send
 * zwp_text_input_v3.enable — otherwise the Rust harness races the broadcast
 * against text-input setup and the first commit_string is dropped by the
 * compositor (see server/compositor/src/text_input.rs: `if !inst.enabled`).
 * 300ms is the observed gap on this device; 500ms is a comfortable margin. */
static void on_map(GtkWidget *widget, gpointer user_data)
{
    (void)widget;
    (void)user_data;
    g_timeout_add(500, emit_ready, NULL);
}

/* Match gtk3-debug-app's Monospace 18pt text. GTK4 dropped
 * gtk_widget_override_font, so we install a CSS rule on the default display. */
static void apply_big_monospace_css(void)
{
    static const char *css =
        "textview, textview text { font-family: Monospace; font-size: 18pt; }";
    GtkCssProvider *provider = gtk_css_provider_new();
    gtk_css_provider_load_from_string(provider, css);
    gtk_style_context_add_provider_for_display(
        gdk_display_get_default(),
        GTK_STYLE_PROVIDER(provider),
        GTK_STYLE_PROVIDER_PRIORITY_USER);
    g_object_unref(provider);
}

static int cmd_text_input(int argc, char *argv[])
{
    (void)argc;
    (void)argv;

    gtk_init();
    apply_big_monospace_css();

    GtkWidget *window = gtk_window_new();
    gtk_window_set_title(GTK_WINDOW(window), "tawc debug: text-input (gtk4)");
    gtk_window_set_default_size(GTK_WINDOW(window), 600, 400);
    g_signal_connect(window, "destroy", G_CALLBACK(on_window_destroy), NULL);

    GtkWidget *scroll = gtk_scrolled_window_new();
    gtk_widget_set_margin_top(scroll, 80);
    gtk_widget_set_margin_bottom(scroll, 40);
    gtk_widget_set_margin_start(scroll, 40);
    gtk_widget_set_margin_end(scroll, 40);
    gtk_window_set_child(GTK_WINDOW(window), scroll);

    text_view = gtk_text_view_new();
    gtk_text_view_set_wrap_mode(GTK_TEXT_VIEW(text_view), GTK_WRAP_WORD_CHAR);
    gtk_scrolled_window_set_child(GTK_SCROLLED_WINDOW(scroll), text_view);

    GtkTextBuffer *buffer = gtk_text_view_get_buffer(GTK_TEXT_VIEW(text_view));
    g_signal_connect(buffer, "changed", G_CALLBACK(on_text_buffer_changed), NULL);
    g_signal_connect(text_view, "preedit-changed", G_CALLBACK(on_preedit_changed), NULL);
    g_signal_connect(buffer, "mark-set", G_CALLBACK(on_mark_set), NULL);
    g_signal_connect(window, "map", G_CALLBACK(on_map), NULL);

    gtk_window_present(GTK_WINDOW(window));
    gtk_widget_grab_focus(text_view);

    main_loop = g_main_loop_new(NULL, FALSE);
    g_main_loop_run(main_loop);
    g_main_loop_unref(main_loop);
    main_loop = NULL;
    return 0;
}

/* --- Command dispatch --------------------------------------------------- */

typedef int (*command_fn)(int argc, char *argv[]);

struct command {
    const char *name;
    const char *description;
    command_fn fn;
};

static const struct command commands[] = {
    { "text-input", "Open a text view for testing text input", cmd_text_input },
    { NULL, NULL, NULL },
};

static void print_usage(const char *prog)
{
    fprintf(stderr, "Usage: %s <command>\n\nCommands:\n", prog);
    for (const struct command *cmd = commands; cmd->name; cmd++)
        fprintf(stderr, "  %-16s %s\n", cmd->name, cmd->description);
}

int main(int argc, char *argv[])
{
    if (argc < 2) {
        print_usage(argv[0]);
        return 1;
    }

    for (const struct command *cmd = commands; cmd->name; cmd++) {
        if (strcmp(argv[1], cmd->name) == 0)
            return cmd->fn(argc - 1, argv + 1);
    }

    fprintf(stderr, "Unknown command: %s\n", argv[1]);
    print_usage(argv[0]);
    return 1;
}
