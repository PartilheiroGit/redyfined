cmake_minimum_required(VERSION 3.18.1)
project(tor_init)

add_library(tor_init SHARED tor_init.c)

find_library(log-lib log)

target_link_libraries(tor_init
    ${log-lib}
    pthread
)
