cmake_minimum_required(VERSION 3.22.1)
project(primecap_launcher C)

add_executable(primecap-launcher primecap_launcher.c)
target_link_libraries(primecap-launcher dl)

add_custom_command(TARGET primecap-launcher POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory
            ${CMAKE_CURRENT_SOURCE_DIR}/../../../scrcpy/build/assets
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:primecap-launcher>
            ${CMAKE_CURRENT_SOURCE_DIR}/../../../scrcpy/build/assets/primecap-launcher)

add_executable(primecap-root-audio primecap_root_audio.c)
target_compile_options(primecap-root-audio PRIVATE -Wall -Wextra -Werror)
add_custom_command(TARGET primecap-root-audio POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory
            ${CMAKE_CURRENT_SOURCE_DIR}/../../../scrcpy/build/assets
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:primecap-root-audio>
            ${CMAKE_CURRENT_SOURCE_DIR}/../../../scrcpy/build/assets/primecap-root-audio)
