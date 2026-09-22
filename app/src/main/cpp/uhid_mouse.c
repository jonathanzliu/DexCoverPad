/*
 * uhid_mouse.c - virtual HID mouse for the Dex Touchpad app.
 *
 * Talks the Linux UHID protocol directly on /dev/uhid. This is original code
 * written for this project; it replaces the third-party prebuilt library the
 * app used to bundle.
 *
 * UHID event numbers follow include/uapi/linux/uhid.h. Note that they are NOT
 * the "obvious" values: the legacy entries take the low slots.
 *
 *   __UHID_LEGACY_CREATE   = 0    UHID_DESTROY         = 1
 *   __UHID_LEGACY_INPUT    = 8    UHID_CREATE2         = 11
 *   UHID_INPUT2            = 12
 *
 * The device is a 4-byte relative mouse report: [buttons][X][Y][wheel]. The
 * button state is kept here and mirrored into every movement report, which is
 * what makes dragging work.
 *
 * SPDX-License-Identifier: MIT
 */

#include <jni.h>
#include <android/log.h>

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

#define TAG "UhidNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define UHID_DEVICE_PATH "/dev/uhid"

/* include/uapi/linux/uhid.h (Linux 6.1) */
#define UHID_DESTROY 1
#define UHID_CREATE2 11
#define UHID_INPUT2 12

#define HID_MAX_DESCRIPTOR_SIZE 4096
#define UHID_DATA_MAX 4096

struct uhid_create2_req {
    uint8_t name[128];
    uint8_t phys[64];
    uint8_t uniq[64];
    uint16_t rd_size;
    uint16_t bus;
    uint32_t vendor;
    uint32_t product;
    uint32_t version;
    uint32_t country;
    uint8_t rd_data[HID_MAX_DESCRIPTOR_SIZE];
} __attribute__((packed));

struct uhid_input2_req {
    uint16_t size;
    uint8_t data[UHID_DATA_MAX];
} __attribute__((packed));

struct uhid_event {
    uint32_t type;
    union {
        struct uhid_create2_req create2;
        struct uhid_input2_req input2;
        uint8_t reserved[sizeof(struct uhid_create2_req)];
    } u;
} __attribute__((packed));

#define EVENT_SIZE ((int)sizeof(struct uhid_event))
#define REPORT_SIZE 4

/* Standard 3-button + wheel relative mouse. 4-byte report. */
static const uint8_t kReportDescriptor[] = {
    0x05, 0x01,             /* Usage Page (Generic Desktop)        */
    0x09, 0x02,             /* Usage (Mouse)                       */
    0xA1, 0x01,             /* Collection (Application)            */
    0x09, 0x01,             /*   Usage (Pointer)                   */
    0xA1, 0x00,             /*   Collection (Physical)             */
    0x05, 0x09,             /*     Usage Page (Button)             */
    0x19, 0x01,             /*     Usage Minimum (1)               */
    0x29, 0x03,             /*     Usage Maximum (3)               */
    0x15, 0x00,             /*     Logical Minimum (0)             */
    0x25, 0x01,             /*     Logical Maximum (1)             */
    0x95, 0x03,             /*     Report Count (3)                */
    0x75, 0x01,             /*     Report Size (1)                 */
    0x81, 0x02,             /*     Input (Data,Var,Abs)            */
    0x95, 0x01,             /*     Report Count (1)                */
    0x75, 0x05,             /*     Report Size (5)                 */
    0x81, 0x03,             /*     Input (Const,Var,Abs)           */
    0x05, 0x01,             /*     Usage Page (Generic Desktop)    */
    0x09, 0x30,             /*     Usage (X)                       */
    0x09, 0x31,             /*     Usage (Y)                       */
    0x09, 0x38,             /*     Usage (Wheel)                   */
    0x15, 0x81,             /*     Logical Minimum (-127)          */
    0x25, 0x7F,             /*     Logical Maximum (127)           */
    0x75, 0x08,             /*     Report Size (8)                 */
    0x95, 0x03,             /*     Report Count (3)                */
    0x81, 0x06,             /*     Input (Data,Var,Rel)            */
    0xC0,                   /*   End Collection                    */
    0xC0                    /* End Collection                      */
};

static int g_fd = -1;
static uint8_t g_buttons = 0;

static uint8_t clamp_axis(int value) {
    if (value > 127) return 127;
    if (value < -127) return (uint8_t)(-127);
    return (uint8_t)(int8_t)value;
}

static void write_report(int dx, int dy, int wheel) {
    if (g_fd < 0) return;

    struct uhid_event event;
    memset(&event, 0, sizeof(event));
    event.type = UHID_INPUT2;
    event.u.input2.size = REPORT_SIZE;
    event.u.input2.data[0] = g_buttons;
    event.u.input2.data[1] = clamp_axis(dx);
    event.u.input2.data[2] = clamp_axis(dy);
    event.u.input2.data[3] = clamp_axis(wheel);

    ssize_t written = write(g_fd, &event, sizeof(event));
    if (written < 0) {
        LOGE("write HID report failed: %s", strerror(errno));
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_dex_1touchpad_services_UhidNative_nativeCreate(JNIEnv *env, jclass clazz,
                                                                jstring jname) {
    if (g_fd >= 0) return JNI_TRUE;

    int fd = open(UHID_DEVICE_PATH, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        LOGE("open %s failed: %s", UHID_DEVICE_PATH, strerror(errno));
        return JNI_FALSE;
    }

    struct uhid_event event;
    memset(&event, 0, sizeof(event));
    event.type = UHID_CREATE2;

    if (jname != NULL) {
        const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
        if (name != NULL) {
            strncpy((char *)event.u.create2.name, name, sizeof(event.u.create2.name) - 1);
            (*env)->ReleaseStringUTFChars(env, jname, name);
        }
    }

    event.u.create2.rd_size = (uint16_t)sizeof(kReportDescriptor);
    event.u.create2.bus = 0x03;      /* BUS_USB */
    event.u.create2.vendor = 0x1234;
    event.u.create2.product = 0x5678;
    event.u.create2.version = 1;
    event.u.create2.country = 0;
    memcpy(event.u.create2.rd_data, kReportDescriptor, sizeof(kReportDescriptor));

    ssize_t written = write(fd, &event, sizeof(event));
    if (written < 0) {
        LOGE("create UHid device failed: %s", strerror(errno));
        close(fd);
        return JNI_FALSE;
    }

    g_fd = fd;
    g_buttons = 0;
    LOGI("UHid device created (%d-byte event, %zu-byte descriptor)", EVENT_SIZE,
         sizeof(kReportDescriptor));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_dex_1touchpad_services_UhidNative_nativeClose(JNIEnv *env, jclass clazz) {
    if (g_fd < 0) return;

    struct uhid_event event;
    memset(&event, 0, sizeof(event));
    event.type = UHID_DESTROY;
    write(g_fd, &event, sizeof(event));

    close(g_fd);
    g_fd = -1;
    g_buttons = 0;
    LOGI("UHid device closed");
}

JNIEXPORT void JNICALL
Java_com_example_dex_1touchpad_services_UhidNative_nativeMove(JNIEnv *env, jclass clazz,
                                                              jint dx, jint dy) {
    write_report(dx, dy, 0);
}

JNIEXPORT void JNICALL
Java_com_example_dex_1touchpad_services_UhidNative_nativeButton(JNIEnv *env, jclass clazz,
                                                                jint buttonCode,
                                                                jboolean pressed) {
    uint8_t bit;
    switch (buttonCode) {
        case 1: bit = 0x01; break;   /* left */
        case 2: bit = 0x02; break;   /* right */
        case 3: bit = 0x04; break;   /* middle */
        default:
            LOGW("unknown button code %d", buttonCode);
            return;
    }
    if (pressed) {
        g_buttons |= bit;
    } else {
        g_buttons &= (uint8_t)~bit;
    }
    /* Report the new button state immediately. */
    write_report(0, 0, 0);
}

JNIEXPORT void JNICALL
Java_com_example_dex_1touchpad_services_UhidNative_nativeScroll(JNIEnv *env, jclass clazz,
                                                                jint delta) {
    write_report(0, 0, delta);
}

/* ------------------------------------------------------------------ */
/* Second virtual device: a keyboard, used for the Ctrl modifier of    */
/* Ctrl+scroll (pinch-to-zoom).                                        */
/* ------------------------------------------------------------------ */

/* Standard boot keyboard: [modifiers][reserved][6 keys] = 8 bytes. */
static const uint8_t kKeyboardDescriptor[] = {
    0x05, 0x01,             /* Usage Page (Generic Desktop)        */
    0x09, 0x06,             /* Usage (Keyboard)                    */
    0xA1, 0x01,             /* Collection (Application)            */
    0x05, 0x07,             /*   Usage Page (Keyboard)             */
    0x19, 0xE0,             /*   Usage Minimum (Left Control)       */
    0x29, 0xE7,             /*   Usage Maximum (Right GUI)          */
    0x15, 0x00,             /*   Logical Minimum (0)                */
    0x25, 0x01,             /*   Logical Maximum (1)                */
    0x75, 0x01,             /*   Report Size (1)                    */
    0x95, 0x08,             /*   Report Count (8)                   */
    0x81, 0x02,             /*   Input (Data,Var,Abs)  -> modifiers */
    0x95, 0x01,             /*   Report Count (1)                   */
    0x75, 0x08,             /*   Report Size (8)                    */
    0x81, 0x01,             /*   Input (Const)         -> reserved  */
    0x95, 0x06,             /*   Report Count (6)                   */
    0x75, 0x08,             /*   Report Size (8)                    */
    0x15, 0x00,             /*   Logical Minimum (0)                */
    0x25, 0x65,             /*   Logical Maximum (101)              */
    0x05, 0x07,             /*   Usage Page (Keyboard)              */
    0x19, 0x00,             /*   Usage Minimum (0)                  */
    0x29, 0x65,             /*   Usage Maximum (101)                */
    0x81, 0x00,             /*   Input (Data,Array)                 */
    0xC0                    /* End Collection                       */
};

#define KEYBOARD_REPORT_SIZE 8
#define KEY_MAX_USAGE 0x65

static int g_kbd_fd = -1;
static uint8_t g_modifiers = 0;
static uint8_t g_keys[6] = {0, 0, 0, 0, 0, 0};

static void write_key_report(void) {
    if (g_kbd_fd < 0) return;

    struct uhid_event event;
    memset(&event, 0, sizeof(event));
    event.type = UHID_INPUT2;
    event.u.input2.size = KEYBOARD_REPORT_SIZE;
    event.u.input2.data[0] = g_modifiers;
    event.u.input2.data[1] = 0;
    memcpy(&event.u.input2.data[2], g_keys, sizeof(g_keys));

    if (write(g_kbd_fd, &event, sizeof(event)) < 0) {
        LOGE("write keyboard report failed: %s", strerror(errno));
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_dex_1touchpad_services_UhidNative_nativeKeyboardCreate(JNIEnv *env, jclass clazz,
                                                                        jstring jname) {
    if (g_kbd_fd >= 0) return JNI_TRUE;

    int fd = open(UHID_DEVICE_PATH, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        LOGE("open %s (keyboard) failed: %s", UHID_DEVICE_PATH, strerror(errno));
        return JNI_FALSE;
    }

    struct uhid_event event;
    memset(&event, 0, sizeof(event));
    event.type = UHID_CREATE2;

    if (jname != NULL) {
        const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
        if (name != NULL) {
            strncpy((char *)event.u.create2.name, name, sizeof(event.u.create2.name) - 1);
            (*env)->ReleaseStringUTFChars(env, jname, name);
        }
    }

    event.u.create2.rd_size = (uint16_t)sizeof(kKeyboardDescriptor);
    event.u.create2.bus = 0x03;
    event.u.create2.vendor = 0x1234;
    event.u.create2.product = 0x5679;
    event.u.create2.version = 1;
    memcpy(event.u.create2.rd_data, kKeyboardDescriptor, sizeof(kKeyboardDescriptor));

    if (write(fd, &event, sizeof(event)) < 0) {
        LOGE("create UHid keyboard failed: %s", strerror(errno));
        close(fd);
        return JNI_FALSE;
    }

    g_kbd_fd = fd;
    g_modifiers = 0;
    memset(g_keys, 0, sizeof(g_keys));
    LOGI("UHid keyboard created");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_dex_1touchpad_services_UhidNative_nativeKeyboardClose(JNIEnv *env, jclass clazz) {
    if (g_kbd_fd < 0) return;

    /* Release everything first so no key stays stuck. */
    g_modifiers = 0;
    memset(g_keys, 0, sizeof(g_keys));
    write_key_report();

    struct uhid_event event;
    memset(&event, 0, sizeof(event));
    event.type = UHID_DESTROY;
    write(g_kbd_fd, &event, sizeof(event));

    close(g_kbd_fd);
    g_kbd_fd = -1;
    LOGI("UHid keyboard closed");
}

/*
 * Press or release one HID keyboard usage. Modifiers are 0xE0..0xE7; any other
 * usage is added to / removed from the 6-key rollover array.
 */
JNIEXPORT void JNICALL
Java_com_example_dex_1touchpad_services_UhidNative_nativeKey(JNIEnv *env, jclass clazz,
                                                             jint usage, jboolean pressed) {
    if (g_kbd_fd < 0) return;
    if (usage < 0 || usage > 0xE7) return;

    if (usage >= 0xE0) {
        uint8_t bit = (uint8_t)(1u << (usage - 0xE0));
        if (pressed) {
            g_modifiers |= bit;
        } else {
            g_modifiers &= (uint8_t)~bit;
        }
        write_key_report();
        return;
    }

    int slot = -1;
    for (int i = 0; i < 6; i++) {
        if (g_keys[i] == (uint8_t)usage) {
            slot = i;
            break;
        }
    }

    if (pressed) {
        if (slot >= 0) return; /* already down */
        for (int i = 0; i < 6; i++) {
            if (g_keys[i] == 0) {
                g_keys[i] = (uint8_t)usage;
                write_key_report();
                return;
            }
        }
        LOGW("keyboard rollover full, dropping usage 0x%02X", usage);
        return;
    }

    if (slot < 0) return; /* not down */
    g_keys[slot] = 0;
    write_key_report();
}
