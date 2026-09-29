file(GLOB_RECURSE app_sources ${CMAKE_SOURCE_DIR}/src/*.*)
idf_component_register(SRCS ${app_sources} REQUIRES usb usb_host_cdc_acm)
# crash.cpp: keep the reason and a backtrace of a crash in RTC memory across the reboot
target_link_libraries(${COMPONENT_LIB} INTERFACE "-Wl,--wrap=esp_panic_handler" "-Wl,--wrap=panic_abort"
                      "-Wl,--wrap=_esp_error_check_failed")
