cmake_minimum_required(VERSION 3.16.0)
include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(printhost-esp32)

# IDF 4.4 bug: lwIP's mem.c only puts its buffers in PSRAM when the OLD option name
# CONFIG_WIFI_LWIP_ALLOCATION_FROM_SPIRAM_FIRST is defined, and that alias is generated only for the
# plain ESP32 target (components/esp32/sdkconfig.rename), not for the S3. So on this board every TCP
# segment of the camera stream (~1.5 KB each, below SPIRAM_MALLOC_ALWAYSINTERNAL) went to internal RAM,
# which Wi-Fi itself lives on: free internal RAM fell to 9 KB while someone watched the camera.
# Give lwIP the old name so CONFIG_SPIRAM_TRY_ALLOCATE_WIFI_LWIP really applies to it.
if(CONFIG_SPIRAM_TRY_ALLOCATE_WIFI_LWIP)
  idf_component_get_property(lwip_lib lwip COMPONENT_LIB)
  target_compile_definitions(${lwip_lib} PRIVATE CONFIG_WIFI_LWIP_ALLOCATION_FROM_SPIRAM_FIRST=1)
endif()
