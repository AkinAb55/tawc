/*
 * gtk3-debug-app: A GTK3 debug/test app for the tawc compositor.
 *
 * Subcommand-based CLI. Each command opens a specific UI for testing
 * a compositor feature. Structured output (TAWC_DEBUG: prefix) is
 * parsed by the integration test harness on the host.
 *
 * Usage: gtk3-debug-app <command>
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

static GtkWidget *text_view;

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

static gboolean on_map_event(GtkWidget *widget, GdkEvent *event, gpointer user_data)
{
    (void)widget;
    (void)event;
    (void)user_data;
    /* Defer READY to next idle so the surface is fully committed */
    g_idle_add(emit_ready, NULL);
    return FALSE;
}

static int cmd_text_input(int argc, char *argv[])
{
    (void)argc;
    (void)argv;

    gtk_init(&argc, &argv);

    GtkWidget *window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    gtk_window_set_title(GTK_WINDOW(window), "tawc debug: text-input");
    gtk_window_set_default_size(GTK_WINDOW(window), 600, 400);
    g_signal_connect(window, "destroy", G_CALLBACK(gtk_main_quit), NULL);

    /* Scrolled window containing a text view, padded away from edges / camera cutout */
    GtkWidget *scroll = gtk_scrolled_window_new(NULL, NULL);
    gtk_widget_set_margin_top(scroll, 80);
    gtk_widget_set_margin_bottom(scroll, 40);
    gtk_widget_set_margin_start(scroll, 40);
    gtk_widget_set_margin_end(scroll, 40);
    gtk_container_add(GTK_CONTAINER(window), scroll);

    text_view = gtk_text_view_new();
    gtk_text_view_set_wrap_mode(GTK_TEXT_VIEW(text_view), GTK_WRAP_WORD_CHAR);
    /* Make the font large for easy visual debugging */
    PangoFontDescription *font = pango_font_description_from_string("Monospace 18");
    gtk_widget_override_font(text_view, font);
    pango_font_description_free(font);
    gtk_container_add(GTK_CONTAINER(scroll), text_view);

    /* Connect signals */
    GtkTextBuffer *buffer = gtk_text_view_get_buffer(GTK_TEXT_VIEW(text_view));
    g_signal_connect(buffer, "changed", G_CALLBACK(on_text_buffer_changed), NULL);
    g_signal_connect(text_view, "preedit-changed", G_CALLBACK(on_preedit_changed), NULL);
    g_signal_connect(buffer, "mark-set", G_CALLBACK(on_mark_set), NULL);
    g_signal_connect(window, "map-event", G_CALLBACK(on_map_event), NULL);

    gtk_widget_show_all(window);
    gtk_widget_grab_focus(text_view);

    gtk_main();
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
