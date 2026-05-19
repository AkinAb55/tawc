# Tess's Android Wayland Compositor
TAWC allows you to run desktop Linux apps on Android without root, and with hardware accelerated graphics. It consists of a Smithay-based Wayland compositor, tawcroot (a higher performance PRoot alternative), android app and everything else needed to install a distro and run apps.

TAWC is a vibecoded project, most of the code is not written or reviewed by humans. It was started with Opus 4.6 and now I use 4.7 and GPT 5.5. Integration tests, automated documentation and automated code review/cleanup are used to try to keep everything in line.

## High-level design
- A Linux distro such as Arch Linux Arm is downloaded and extracted
- Linux programs, including the distro's native package manager, are run "inside" the distro's rootfs using `tawcroot`
- `tawcroot` emulates syscalls to overcome rootless Android's limitations, it's similar to PRoot but uses gVisor's systrap approach
- `libhybris` allows glibc Linux programs to load the standard Android graphics drivers
- Upstream  `libhybris` doesn't work on stock Android, but [our fork](https://github.com/wmww/libhybris) does
- There's also some work on alternative graphics stacks such as using gfxstream, but that's still in-progress
- The app contains a Smithay-based Wayland compositor which presents Linux apps alongside Android apps in the app switcher
- XWayland is included and wired up for hardware accelerated X11 support
- Various other app features (launcher, task manager, etc) tie it all together
