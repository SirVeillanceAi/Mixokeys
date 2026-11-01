cmake_minimum_required(VERSION 3.22.1)
project(mixokeys_synth C)

# FluidSynth comes prebuilt (downloaded by tools/fetch-native.sh into src/main/jniLibs).
set(JNILIBS ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
add_library(fluidsynth SHARED IMPORTED)
set_target_properties(fluidsynth PROPERTIES IMPORTED_LOCATION ${JNILIBS}/libfluidsynth.so)

add_library(mixokeys_synth SHARED synth.c)
target_include_directories(mixokeys_synth PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(mixokeys_synth fluidsynth android log)
target_link_options(mixokeys_synth PRIVATE "-Wl,-z,max-page-size=16384")
