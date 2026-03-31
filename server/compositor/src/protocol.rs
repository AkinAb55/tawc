pub mod tawc_buffer_v1 {
    pub mod server {
        use wayland_server;
        use wayland_server::protocol::*;
        pub mod __interfaces {
            use wayland_server::backend as wayland_backend;
            use wayland_server::protocol::__interfaces::*;
            wayland_scanner::generate_interfaces!("protocols/tawc_buffer_v1.xml");
        }
        use self::__interfaces::*;

        wayland_scanner::generate_server_code!("protocols/tawc_buffer_v1.xml");
    }
}
