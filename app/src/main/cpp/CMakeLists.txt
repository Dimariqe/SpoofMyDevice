cmake_minimum_required(VERSION 3.22.1)
project("spoofproxy")
add_library(spoofproxy SHARED origdst.c)
target_link_libraries(spoofproxy log)
