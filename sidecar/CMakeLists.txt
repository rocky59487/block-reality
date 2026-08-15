cmake_minimum_required(VERSION 3.20)
project(BlockRealitySidecar CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_compile_definitions(EIGEN_MPL2_ONLY)

# ---------------------------------------------------------------- FrameCore
# The engine is consumed as SOURCE, not vendored into this repo. Point
# FRAMECORE_DIR at Plugins/FrameSolver/Source/FrameCore in an architect_simulator
# checkout. FrameCoreModule.cpp is the only Unreal-only translation unit.
if(NOT DEFINED FRAMECORE_DIR)
    set(FRAMECORE_DIR "$ENV{FRAMECORE_DIR}" CACHE PATH "FrameCore source root")
endif()
if(NOT EXISTS "${FRAMECORE_DIR}/Public/FrameCore/FrameSolver.h")
    message(FATAL_ERROR
        "FRAMECORE_DIR does not point at a FrameCore source tree.\n"
        "  -DFRAMECORE_DIR=/path/to/Plugins/FrameSolver/Source/FrameCore")
endif()

file(GLOB FRAMECORE_SRC "${FRAMECORE_DIR}/Private/*.cpp")
list(FILTER FRAMECORE_SRC EXCLUDE REGEX "FrameCoreModule\\.cpp$")

# ------------------------------------------------------------------- Eigen
if(NOT DEFINED EIGEN_DIR)
    if(DEFINED ENV{EIGEN_DIR})
        set(EIGEN_DIR "$ENV{EIGEN_DIR}" CACHE PATH "Eigen include root")
    elseif(EXISTS "/usr/include/eigen3/Eigen/Core")
        set(EIGEN_DIR "/usr/include/eigen3" CACHE PATH "Eigen include root")
    else()
        message(FATAL_ERROR "Eigen not found. Pass -DEIGEN_DIR=/path/to/eigen")
    endif()
endif()

# ----------------------------------------------------------------- target
add_executable(br-sidecar main.cpp ${FRAMECORE_SRC})

target_include_directories(br-sidecar PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${EIGEN_DIR}"
    "${FRAMECORE_DIR}/Public"
    "${FRAMECORE_DIR}/Private")

# ------------------------------------------------- supernodal Cholesky lane
# FrameCore's supernodal lane needs METIS + OpenBLAS + LAPACKE. It is gated at
# COMPILE time by FRAMECORE_SUPERNODAL, which defaults to 1 in a standalone build.
#
# This sidecar defaults it OFF, and that is not a downgrade:
# SolveOptions::useSupernodalPrimary is false, so every solve already went through
# Eigen's SimplicialLDLT. Compiling the lane out therefore changes no number — the
# gate proves it, 68/68 identical either way — while removing all three native
# dependencies. What is left is Eigen, which is header-only.
#
# That is what makes a portable binary possible: nothing to install, nothing to
# ship alongside, and a Windows cross-build that needs only a compiler.
#
# Turn it back on with -DBR_SUPERNODAL=ON when a structure is large enough for the
# supernodal path to be worth its dependencies (D-008 says building scale is not).
option(BR_SUPERNODAL "Compile FrameCore's supernodal Cholesky lane (needs METIS + OpenBLAS + LAPACKE)" OFF)

if(BR_SUPERNODAL)
    target_compile_definitions(br-sidecar PRIVATE FRAMECORE_SUPERNODAL=1)
    find_library(METIS_LIB    metis)
    find_library(OPENBLAS_LIB NAMES openblas blas)
    find_library(LAPACKE_LIB  lapacke)
    foreach(_lib METIS_LIB OPENBLAS_LIB LAPACKE_LIB)
        if(NOT ${_lib})
            message(FATAL_ERROR "${_lib} not found. Install libmetis-dev libopenblas-dev liblapacke-dev, "
                                "or build with -DBR_SUPERNODAL=OFF (the default).")
        endif()
    endforeach()
    target_link_libraries(br-sidecar PRIVATE ${METIS_LIB} ${OPENBLAS_LIB} ${LAPACKE_LIB})
else()
    target_compile_definitions(br-sidecar PRIVATE FRAMECORE_SUPERNODAL=0)
endif()

# Static-link the toolchain runtime so the binary runs on a machine that has no
# matching libstdc++/libgcc. A structural engine that fails to start because the
# user's distribution ships a different GCC is not a shippable artifact.
if(BR_STATIC_RUNTIME)
    target_link_options(br-sidecar PRIVATE -static-libstdc++ -static-libgcc)
    if(MINGW)
        target_link_options(br-sidecar PRIVATE -static)
    endif()
endif()

# Build (portable, no dependencies but Eigen):
#   cmake -S sidecar -B sidecar/build -DCMAKE_BUILD_TYPE=Release \
#         -DFRAMECORE_DIR=/path/to/Plugins/FrameSolver/Source/FrameCore
#   cmake --build sidecar/build --parallel
# Verify:
#   python3 sidecar/verify.py sidecar/build/br-sidecar
