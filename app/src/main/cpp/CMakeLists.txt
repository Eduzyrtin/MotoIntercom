cmake_minimum_required(VERSION 3.22.1)
project(motointercom C)

# Opus 1.5.2 oficial de xiph (third_party/opus) como biblioteca estática. Sin las partes de redes neuronales
# (DRED, OSCE, Deep PLC): el repositorio no trae los pesos y para voz a 16 kbps no hacen falta.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/opus ${CMAKE_CURRENT_BINARY_DIR}/opus EXCLUDE_FROM_ALL)
# Optimizado también en Debug: sin optimizar, Opus es muy lento (las pruebas en el móvil se hacen con la APK de debug)
target_compile_options(opus PRIVATE -O2)

add_library(motointercom SHARED opus_jni.c)
target_link_libraries(motointercom PRIVATE opus)
