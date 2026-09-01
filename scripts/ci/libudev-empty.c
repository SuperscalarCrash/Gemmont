/*
 * Vivado 2023.2's Flex/WebTalk host probe corrupts the heap when it enumerates
 * devices through Debian 12's libudev.  Batch synthesis does not need device
 * discovery, so provide the small libudev ABI surface used by the probe and
 * return an empty device list.  Do not use this shim for Hardware Manager.
 */

#include <stddef.h>

struct udev { int unused; };
struct udev_device { int unused; };
struct udev_enumerate { int unused; };
struct udev_list_entry { int unused; };

static struct udev empty_udev;
static struct udev_enumerate empty_enumerate;

struct udev *udev_new(void) { return &empty_udev; }
struct udev *udev_ref(struct udev *udev) { return udev; }
struct udev *udev_unref(struct udev *udev) { (void)udev; return NULL; }

struct udev_enumerate *udev_enumerate_new(struct udev *udev) {
    (void)udev;
    return &empty_enumerate;
}
struct udev_enumerate *udev_enumerate_ref(struct udev_enumerate *enumerate) {
    return enumerate;
}
struct udev_enumerate *udev_enumerate_unref(struct udev_enumerate *enumerate) {
    (void)enumerate;
    return NULL;
}
int udev_enumerate_scan_devices(struct udev_enumerate *enumerate) {
    (void)enumerate;
    return 0;
}
struct udev_list_entry *udev_enumerate_get_list_entry(
    struct udev_enumerate *enumerate) {
    (void)enumerate;
    return NULL;
}

struct udev_list_entry *udev_list_entry_get_next(struct udev_list_entry *entry) {
    (void)entry;
    return NULL;
}
const char *udev_list_entry_get_name(struct udev_list_entry *entry) {
    (void)entry;
    return NULL;
}
const char *udev_list_entry_get_value(struct udev_list_entry *entry) {
    (void)entry;
    return NULL;
}

struct udev_device *udev_device_new_from_syspath(
    struct udev *udev, const char *syspath) {
    (void)udev;
    (void)syspath;
    return NULL;
}
struct udev_device *udev_device_unref(struct udev_device *device) {
    (void)device;
    return NULL;
}
const char *udev_device_get_devpath(struct udev_device *device) {
    (void)device;
    return NULL;
}
const char *udev_device_get_subsystem(struct udev_device *device) {
    (void)device;
    return NULL;
}
const char *udev_device_get_devtype(struct udev_device *device) {
    (void)device;
    return NULL;
}
const char *udev_device_get_syspath(struct udev_device *device) {
    (void)device;
    return NULL;
}
const char *udev_device_get_sysname(struct udev_device *device) {
    (void)device;
    return NULL;
}
const char *udev_device_get_sysnum(struct udev_device *device) {
    (void)device;
    return NULL;
}
const char *udev_device_get_devnode(struct udev_device *device) {
    (void)device;
    return NULL;
}
struct udev_list_entry *udev_device_get_properties_list_entry(
    struct udev_device *device) {
    (void)device;
    return NULL;
}
struct udev_list_entry *udev_device_get_devlinks_list_entry(
    struct udev_device *device) {
    (void)device;
    return NULL;
}
