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

# FrameCore's supernodal Cholesky lane needs METIS + BLAS/LAPACKE. It is opt-in
# at runtime (SolveOptions::useSupernodalPrimary defaults to false) but the
# translation units reference the symbols unconditionally, so they must link.
find_library(METIS_LIB    metis)
find_library(OPENBLAS_LIB NAMES openblas blas)
find_library(LAPACKE_LIB  lapacke)
foreach(_lib METIS_LIB OPENBLAS_LIB LAPACKE_LIB)
    if(NOT ${_lib})
        message(FATAL_ERROR "${_lib} not found. Install libmetis-dev libopenblas-dev liblapacke-dev")
    endif()
endforeach()
target_link_libraries(br-sidecar PRIVATE ${METIS_LIB} ${OPENBLAS_LIB} ${LAPACKE_LIB})

# Build:
#   cmake -S sidecar -B sidecar/build -DCMAKE_BUILD_TYPE=Release \
#         -DFRAMECORE_DIR=/path/to/Plugins/FrameSolver/Source/FrameCore
#   cmake --build sidecar/build --parallel
# Verify:
#   python3 sidecar/verify.py sidecar/build/br-sidecar
