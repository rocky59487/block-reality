# CRP setup record

The first two Xvfb checks failed before launching Minecraft: the X keyboard
compiler /usr/bin/xkbcomp was missing. Both failed glxinfo logs and the second
attempt's Xvfb stderr are retained. x11-xkb-utils/libxkbfile1 were then installed
in the Ubuntu WSL environment. Other display tools were extracted into the owned
/home/rocky/br-render-tools directory. The third GL check reports software
llvmpipe (LLVM15.0.7), Mesa23.2.1 and OpenGL4.5. It is not accelerated hardware.
The WSL Unix-socket warning remains visible; no socket permissions were changed.

An initial sudo check requested a password; no password was entered. The package
install used the existing WSL root launch interface, without changing credentials
or sudo policy. An earlier shell extraction loop lost its variable when passed
across Windows/WSL quoting; Python argv-based extraction completed afterwards.
Neither setup error is a functional rendering pass or a mutation oracle.

Client/server probe compilation and the ordinary artifact guard pass. Actual
Minecraft connection and images are still pending at this setup checkpoint.
