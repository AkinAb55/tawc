# Tess's Android Wayland Compositor
tawc allows you to run both CLI and graphical Linux apps on Android without root, and with hardware accelerated graphics. It consists of a Smithay-based Wayland compositor, tawcroot (an alternative to PRoot), an Android app to manage Linux distros and everything else needed.

This is a vibecoded project, most of the code is not written or reviewed by humans. It was started with Opus 4.6 and I've continued to use the latest models as they come out. Integration tests, automated documentation and automated code review/cleanup are used to try to keep everything in line.

## High-level design
- A Linux distro such as Arch Linux Arm or Debian is downloaded and extracted
- Linux programs, including the distro's native package manager, are run "inside" the distro's rootfs using `tawcroot`
- `tawcroot` emulates syscalls to overcome the limitations of rootless Android, it's similar to PRoot but uses gVisor's systrap approach
- `libhybris` allows glibc Linux programs to load the standard Android graphics drivers
- Upstream `libhybris` doesn't work on stock Android, but [our fork](https://github.com/wmww/libhybris) does
- The app contains a Smithay-based Wayland compositor which presents Linux apps alongside Android apps in the app switcher
- XWayland is included and wired up for hardware accelerated X11 support
- Various other app features (terminal, launcher, task manager, etc) tie it all together

See [AGENTS.md](AGENTS.md) for more details.

## Contributions
Anyone may open GitHub issues and PRs, but I make no guarantees of if/when I will respond to them. AI is expected for the actual content of any PRs, but please write out the issue/PR comments yourself.
