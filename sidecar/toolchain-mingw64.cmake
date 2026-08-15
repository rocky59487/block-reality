# Cross-compile br-sidecar.exe for 64-bit Windows from Linux.
#
# Possible only because the supernodal lane is compiled out by default: what remains
# is FrameCore plus header-only Eigen, so the toolchain is the whole dependency list.
#
#   sudo apt-get install -y g++-mingw-w64-x86-64
#   cmake -S sidecar -B sidecar/build-win -DCMAKE_BUILD_TYPE=Release \
#         -DCMAKE_TOOLCHAIN_FILE=sidecar/toolchain-mingw64.cmake \
#         -DBR_STATIC_RUNTIME=ON \
#         -DFRAMECORE_DIR=/path/to/Plugins/FrameSolver/Source/FrameCore
#   cmake --build sidecar/build-win --parallel
#
# BR_STATIC_RUNTIME is what makes the result a single self-contained .exe: without it
# the binary needs libstdc++-6.dll, libgcc_s_seh-1.dll and libwinpthread-1.dll beside
# it, which is three more things to get wrong on someone else's machine.
set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

set(CMAKE_C_COMPILER   x86_64-w64-mingw32-gcc)
set(CMAKE_CXX_COMPILER x86_64-w64-mingw32-g++)
set(CMAKE_RC_COMPILER  x86_64-w64-mingw32-windres)

set(CMAKE_FIND_ROOT_PATH /usr/x86_64-w64-mingw32)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
