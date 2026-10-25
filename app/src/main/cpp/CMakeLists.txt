# One tiny library: hands the native allocator's freed pages back to the system (see NativeHeap.kt).
cmake_minimum_required(VERSION 3.22.1)
project(heappurge C)
add_library(heappurge SHARED heap_purge.c)
