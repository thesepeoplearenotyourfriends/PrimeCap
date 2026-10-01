cmake_minimum_required(VERSION 3.22.1)
project(primecap_launcher C)

add_library(primecap-launcher SHARED primecap_launcher.c)
target_link_libraries(primecap-launcher log dl)
