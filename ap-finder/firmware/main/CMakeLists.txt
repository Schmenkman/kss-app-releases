idf_component_register(
    SRCS "main.c" "apf_parse.c" "apf_proto.c"
    INCLUDE_DIRS "."
    PRIV_REQUIRES esp_wifi esp_event esp_timer nvs_flash esp_driver_usb_serial_jtag
)
