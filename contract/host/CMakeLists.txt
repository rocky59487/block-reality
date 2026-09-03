# contract/host -- the shared BSI host (contract artifact, mirrored by hash).
# Build OUT OF TREE: everything under contract/ is hashed, so never `-B` here.
#   cmake -S contract/host -B build/host -DBSI_HOST_BUILD_TESTS=ON && cmake --build build/host && ctest --test-dir build/host
cmake_minimum_required(VERSION 3.20)
project(bsi_host CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
option(BSI_HOST_BUILD_TESTS "build host_tests, the stub engines and the ctest lanes" OFF)
option(BSI_HOST_DEV_UNPINNED "development only: build even when CONTRACT_SHA256 does not match (never in CI)" OFF)

set(BSI_CONTRACT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/..")
set(BSI_EMBED_OUT "${CMAKE_CURRENT_BINARY_DIR}/bsi_schema_blob.cpp")
include(${CMAKE_CURRENT_SOURCE_DIR}/bsi_schema_embed.cmake)
# re-run the embed when the contract changes
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${BSI_CONTRACT_DIR}/bsi.schema.json" "${BSI_CONTRACT_DIR}/CONTRACT_SHA256")

add_library(bsi_host STATIC
    bsi_schema.cpp bsi_vocab.cpp bsi_writer.cpp bsi_session.cpp bsi_engine_loader.cpp bsi_arena.cpp
    transports/stdio_b64.cpp transports/frame_stdio.cpp transports/arena.cpp
    ${BSI_EMBED_OUT})
target_include_directories(bsi_host PUBLIC ${CMAKE_CURRENT_SOURCE_DIR} ${BSI_CONTRACT_DIR})
target_compile_options(bsi_host PRIVATE -Wall -Wextra)
if(NOT WIN32)
    target_link_libraries(bsi_host PUBLIC ${CMAKE_DL_LIBS} pthread)
endif()

# the generic host process (dev/CI arm); exports bsi_writer_* to dlopen'd engines
add_executable(bsi-hostd bsi_hostd.cpp)
target_link_libraries(bsi-hostd PRIVATE bsi_host)
set_target_properties(bsi-hostd PROPERTIES ENABLE_EXPORTS ON)

# helper for engine authors: an object library with the bsi_capi implementation
add_library(bsi_capi_obj OBJECT bsi_capi.cpp)
target_include_directories(bsi_capi_obj PUBLIC ${CMAKE_CURRENT_SOURCE_DIR} ${BSI_CONTRACT_DIR})
target_compile_definitions(bsi_capi_obj PRIVATE BSI_HOST_STATIC_ENGINE=1)

if(BSI_HOST_BUILD_TESTS)
    enable_testing()
    add_library(bsi_stub_engine SHARED test/stub_engine.cpp)
    target_include_directories(bsi_stub_engine PRIVATE ${BSI_CONTRACT_DIR})
    add_library(bsi_stub_engine_abi2 SHARED test/stub_engine.cpp)
    target_include_directories(bsi_stub_engine_abi2 PRIVATE ${BSI_CONTRACT_DIR})
    target_compile_definitions(bsi_stub_engine_abi2 PRIVATE BSI_STUB_ABI=2u)
    # the in-process C ABI carrying the stub (what run.py --adapter capi drives)
    add_library(bsi_stub_capi SHARED test/stub_engine.cpp $<TARGET_OBJECTS:bsi_capi_obj>)
    target_include_directories(bsi_stub_capi PRIVATE ${BSI_CONTRACT_DIR})
    target_link_libraries(bsi_stub_capi PRIVATE bsi_host)
    # bsi-hostd with the stub statically linked (--engine static)
    add_executable(bsi-hostd-stub bsi_hostd.cpp test/stub_engine.cpp)
    target_compile_definitions(bsi-hostd-stub PRIVATE BSI_HOST_STATIC_ENGINE=1)
    target_link_libraries(bsi-hostd-stub PRIVATE bsi_host)
    add_executable(host_tests test/host_tests.cpp test/stub_engine.cpp $<TARGET_OBJECTS:bsi_capi_obj>)
    target_link_libraries(host_tests PRIVATE bsi_host)
    add_test(NAME host_tests COMMAND host_tests)
    add_test(NAME capi_stub_abi COMMAND bsi-hostd --engine $<TARGET_FILE:bsi_stub_engine_abi2> --version)
    set_tests_properties(capi_stub_abi PROPERTIES PASS_REGULAR_EXPRESSION "bsi-host/")
endif()
