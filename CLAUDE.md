Hi Claude, this project is Tess's Android Wayland Compositor (tawc)

## Notes
[notes.md](notes.md) contains architecture and implementation notes, and should be kept up to date with new choices and discoveries.

This is an agent-written project. Existing code/notes may be wrong in all sorts of ways. Stay vigilant, and always be prepared to surface/fix problems (even when working on something else).

## Workflow
- You often need to debug against a real Android phone, which is provided via adb
- Try to work autonomously when possible, figure out how to close the debugging loop. If you need one-off human help to get your dev loop set up, just ask
- When analyzing screenshots, use a sub-agent so the image doesn't end up in main context.
- If `su` is available on a phone you're testing on, use it instead of `adb root`
- When there's a script to do something (eg create/destroy the chroot) always use it instead of oneshotting commands
- If there's a problem with a script (or any file in the repo), feel free to edit it

## Wayland buffers
We support both hardware buffers and SHM buffers, but the goal is to make hardware buffers work for most apps in most cases without their pixels ever having to touch the CPU. To this end, we tint SHM buffers magenta so it's obvious when they're being used (which may or may not be what we want in a given context). Do not remove this magenta tinting unless explicitly asked to.

## Device Support
The goal of this project is to run on all modern Android phones without requiring firmware modifications. This means requiring open source GPU drivers or bionic patches is not viable. Requiring root is viable (especially for testing), but ideally it could even be run without root.

## Safety
I'm letting you play with my phone, try not to fuck it up.

## Organization
Avoid junking up devices (eg delete screenshots you take when you're done with them). On the phone things should generally stay in `/data/local/arch-chroot/`, `/data/local/claude-debug` (**NOT** `/data/local/tmp`)
