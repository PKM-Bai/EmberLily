#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <mgba/core/blip_buf.h>
#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/serialize.h>
#include <mgba/core/version.h>
#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/memory.h>
#include <mgba/internal/gba/savedata.h>
#include <mgba/internal/gba/serialize.h>
#include <mgba-util/vfs.h>

#define JNI_METHOD(name) Java_com_emberlily_app_emulation_NativeCore_##name
#define FRAME_PIXELS (240 * 160)
#define AUDIO_FRAMES 2048
#define MAX_READ (1024 * 1024)
#define MAX_PATCHES 64
#define MAX_PATCH_BYTES (16 * 1024)
#define MAX_BATTERY (0x20000 + 16)
#define MAX_STATE (1024 * 1024)
#define STATE_HEADER 128
#define STATE_VERSION 2
#define MGBA_REVISION "26b7884bc25a5933960f3cdcd98bac1ae14d42e2"

_Static_assert(sizeof(color_t) == 4, "The Android bridge requires 32-bit mGBA color");

struct Session {
    jlong id;
    struct mCore* core;
    color_t video[FRAME_PIXELS];
    jint argb[FRAME_PIXELS];
    short audio[AUDIO_FRAMES * 2];
    char* rom_path;
    char* save_path;
    uint32_t rom_size;
    uint8_t rom_sha256[32];
    char core_identity[64];
    bool has_frame;
    bool poisoned;
    struct Session* next;
};

/* Serialization also protects close against accidental calls from another thread. */
static pthread_mutex_t sessions_lock = PTHREAD_MUTEX_INITIALIZER;
static struct Session* sessions;
static jlong next_id = 1;
static const uint8_t state_magic[8] = {'E', 'L', 'S', 'T', 'A', 'T', 'E', '2'};

static void throw_exception(JNIEnv* env, const char* name, const char* message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass type = (*env)->FindClass(env, name);
    if (type) (*env)->ThrowNew(env, type, message);
}

static struct Session* find_session(JNIEnv* env, jlong id) {
    for (struct Session* s = sessions; s; s = s->next) {
        if (s->id == id) {
            if (!s->poisoned) return s;
            throw_exception(env, "java/lang/IllegalStateException", "GBA core failed to roll back; close it and restore a saved recovery point");
            return NULL;
        }
    }
    throw_exception(env, "java/lang/IllegalStateException", "GBA core is closed or handle is invalid");
    return NULL;
}

static char* copy_path(JNIEnv* env, jstring path) {
    if (!path) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Path is required");
        return NULL;
    }
    const char* utf = (*env)->GetStringUTFChars(env, path, NULL);
    if (!utf) return NULL;
    char* result = NULL;
    if (utf[0] != '/' || strlen(utf) >= PATH_MAX - 16) {
        throw_exception(env, "java/lang/IllegalArgumentException", "An absolute app-private path is required");
    } else {
        result = strdup(utf);
        if (!result) throw_exception(env, "java/lang/OutOfMemoryError", "Cannot allocate path");
    }
    (*env)->ReleaseStringUTFChars(env, path, utf);
    return result;
}

static bool same_file(const char* a, const char* b) {
    if (!strcmp(a, b)) return true;
    struct stat sa, sb;
    return stat(a, &sa) == 0 && stat(b, &sb) == 0 &&
        sa.st_dev == sb.st_dev && sa.st_ino == sb.st_ino;
}

/* Hash the supplied file, not mGBA's cartridge mapping: multiboot inputs have
 * no cartridge romCrc32. Android's SHA-256 provider avoids an extra native
 * dependency and is independent of the optional mGBA compression libraries. */
static bool hash_rom(JNIEnv* env, struct VFile* file, size_t size, uint8_t digest[32]) {
    if ((*env)->PushLocalFrame(env, 8) < 0) return false;
    bool ok = false;
    jclass type = (*env)->FindClass(env, "java/security/MessageDigest");
    if (!type) goto done;
    jmethodID get_instance = (*env)->GetStaticMethodID(env, type, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jmethodID update = (*env)->GetMethodID(env, type, "update", "([BII)V");
    jmethodID finish = (*env)->GetMethodID(env, type, "digest", "()[B");
    if (!get_instance || !update || !finish) goto done;
    jstring algorithm = (*env)->NewStringUTF(env, "SHA-256");
    if (!algorithm) goto done;
    jobject hasher = (*env)->CallStaticObjectMethod(env, type, get_instance, algorithm);
    if ((*env)->ExceptionCheck(env) || !hasher) goto done;
    jbyteArray chunk = (*env)->NewByteArray(env, 65536);
    if (!chunk || file->seek(file, 0, SEEK_SET) != 0) goto done;
    uint8_t buffer[65536];
    size_t offset = 0;
    while (offset < size) {
        size_t wanted = size - offset;
        if (wanted > sizeof(buffer)) wanted = sizeof(buffer);
        ssize_t count = file->read(file, buffer, wanted);
        if (count <= 0) goto done;
        (*env)->SetByteArrayRegion(env, chunk, 0, (jsize)count, (const jbyte*)buffer);
        if ((*env)->ExceptionCheck(env)) goto done;
        (*env)->CallVoidMethod(env, hasher, update, chunk, 0, (jint)count);
        if ((*env)->ExceptionCheck(env)) goto done;
        offset += (size_t)count;
    }
    if (file->read(file, buffer, 1) != 0) goto done;
    jbyteArray result = (*env)->CallObjectMethod(env, hasher, finish);
    if ((*env)->ExceptionCheck(env) || !result || (*env)->GetArrayLength(env, result) != 32) goto done;
    (*env)->GetByteArrayRegion(env, result, 0, 32, (jbyte*)digest);
    ok = !(*env)->ExceptionCheck(env);
done:
    if (file->seek(file, 0, SEEK_SET) != 0) ok = false;
    (*env)->PopLocalFrame(env, NULL);
    return ok;
}

static bool state_path_allowed(const struct Session* s, const char* path) {
    (void)s;
    struct stat st;
    if (lstat(path, &st) == 0 && !S_ISREG(st.st_mode)) return false;
    for (const struct Session* open_session = sessions; open_session; open_session = open_session->next) {
        if (same_file(path, open_session->rom_path) || same_file(path, open_session->save_path)) return false;
    }
    return true;
}

static bool flush_battery(struct Session* s) {
    struct GBA* gba = s->core->board;
    struct GBASavedata* save = &gba->memory.savedata;
    if (!save->vf || save->vf != save->realVf) return false;
    if (save->data && !save->vf->sync(save->vf, save->data, GBASavedataSize(save))) return false;
    if (save->data) {
        GBASavedataRTCWrite(save);
        if (!save->data) return false;
    }
    /* The FD backend uses fsync here; syncing its mapping alone is asynchronous. */
    return save->vf->sync(save->vf, NULL, 0);
}

static void destroy_session(struct Session* s) {
    if (s->core) {
        if (s->poisoned) {
            struct GBASavedata* save = &((struct GBA*)s->core->board)->memory.savedata;
            /* Upstream unmap calls msync. A failed rollback must not explicitly
             * flush again. MAP_SHARED pages may already have reached disk; the
             * host must preserve its last complete resume file for recovery. */
            if (save->data && save->vf == save->realVf) {
                munmap(save->data, GBASavedataSize(save));
                save->data = NULL;
                save->currentBank = NULL;
            }
            save->maskWriteback = false;
        }
        mCoreConfigDeinit(&s->core->config);
        s->core->deinit(s->core);
    }
    free(s->rom_path);
    free(s->save_path);
    free(s);
}

static uint32_t read_u32(const uint8_t* p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

static void write_u32(uint8_t* p, uint32_t n) {
    for (unsigned i = 0; i < 4; ++i) p[i] = (uint8_t)(n >> (i * 8));
}

/* Detect accidental truncation/corruption before passing state to upstream. */
static uint32_t payload_hash(const uint8_t* p, size_t size) {
    uint32_t hash = 2166136261u;
    for (size_t i = 0; i < size; ++i) hash = (hash ^ p[i]) * 16777619u;
    return hash;
}

static bool valid_battery_size(uint8_t type, size_t size) {
    switch (type) {
        case SAVEDATA_SRAM: return size == SIZE_CART_SRAM;
        case SAVEDATA_SRAM512: return size == SIZE_CART_SRAM512;
        case SAVEDATA_FLASH512: return size == SIZE_CART_FLASH512;
        case SAVEDATA_FLASH1M: return size == SIZE_CART_FLASH1M;
        case SAVEDATA_EEPROM: return size == SIZE_CART_EEPROM;
        case SAVEDATA_EEPROM512: return size == SIZE_CART_EEPROM512;
        case SAVEDATA_FORCE_NONE: return size == 0;
        case 255: return size <= MAX_BATTERY; /* SAVEDATA_AUTODETECT in the wire format. */
        default: return false;
    }
}

/* Internal format v2: 128-byte header, raw mGBA state, battery, generic RTC.
 * LE fields: raw/battery/RTC sizes at 8/12/16, source file size at 20,
 * payload hash at 24, version at 28, SHA-256 at 32, core identity at 64.
 * Version 1 intentionally is not accepted: it identified only cartridges. */
static bool validate_state(struct Session* s, const uint8_t* data, size_t size) {
    if (size < STATE_HEADER || size > MAX_STATE || memcmp(data, state_magic, 8) ||
        read_u32(data + 28) != STATE_VERSION || read_u32(data + 20) != s->rom_size ||
        memcmp(data + 32, s->rom_sha256, 32) || memcmp(data + 64, s->core_identity, 64)) return false;
    size_t raw = read_u32(data + 8), battery = read_u32(data + 12), rtc = read_u32(data + 16);
    if (raw != s->core->stateSize(s->core) || battery > MAX_BATTERY ||
        rtc != sizeof(struct mRTCGenericState) || STATE_HEADER + raw + battery + rtc != size) return false;
    const struct GBASerializedState* state = (const void*)(data + STATE_HEADER);
    if (!valid_battery_size(state->savedata.type, battery) ||
        read_u32(data + STATE_HEADER + raw + battery) > RTC_WALLCLOCK_OFFSET) return false;
    return read_u32(data + 24) == payload_hash(data + STATE_HEADER, size - STATE_HEADER);
}

static uint8_t* capture_state(struct Session* s, size_t* result_size) {
    struct mCore* core = s->core;
    struct GBA* gba = core->board;
    size_t expected = GBASavedataSize(&gba->memory.savedata);
    if (expected > MAX_BATTERY) return NULL;
    void* battery = NULL;
    size_t battery_size = core->savedataClone(core, &battery);
    if (battery_size != expected || (battery_size && !battery)) {
        free(battery);
        return NULL;
    }
    struct mStateExtdataItem rtc = {0};
    core->rtc.d.serialize(&core->rtc.d, &rtc);
    size_t raw_size = core->stateSize(core);
    size_t size = STATE_HEADER + raw_size + battery_size + sizeof(struct mRTCGenericState);
    uint8_t* data = NULL;
    if (rtc.data && rtc.size == sizeof(struct mRTCGenericState) && size <= MAX_STATE) data = calloc(1, size);
    if (data) {
        memcpy(data, state_magic, 8);
        write_u32(data + 8, (uint32_t)raw_size);
        write_u32(data + 12, (uint32_t)battery_size);
        write_u32(data + 16, (uint32_t)rtc.size);
        write_u32(data + 20, s->rom_size);
        write_u32(data + 28, STATE_VERSION);
        memcpy(data + 32, s->rom_sha256, 32);
        memcpy(data + 64, s->core_identity, 64);
        bool saved = core->saveState(core, data + STATE_HEADER);
        if (battery_size) memcpy(data + STATE_HEADER + raw_size, battery, battery_size);
        memcpy(data + STATE_HEADER + raw_size + battery_size, rtc.data, (size_t)rtc.size);
        write_u32(data + 24, payload_hash(data + STATE_HEADER, size - STATE_HEADER));
        if (!saved || !validate_state(s, data, size)) {
            free(data);
            data = NULL;
        }
    }
    free(battery);
    if (rtc.clean) rtc.clean(rtc.data);
    if (data) *result_size = size;
    return data;
}

static bool apply_state(struct Session* s, const uint8_t* data) {
    struct mCore* core = s->core;
    size_t raw = read_u32(data + 8), battery = read_u32(data + 12);
    if (!core->loadState(core, data + STATE_HEADER)) return false;
    if (!core->savedataRestore(core, data + STATE_HEADER + raw, battery, true)) return false;
    struct GBASavedata* save = &((struct GBA*)core->board)->memory.savedata;
    /* A pre-save/autodetect state must not retain a later save file's contents. */
    if (!save->data) {
        save->vf->truncate(save->vf, battery);
        if (save->vf->size(save->vf) != (ssize_t)battery) return false;
    }
    struct mStateExtdataItem rtc = {
        .size = (int32_t)read_u32(data + 16),
        .data = (void*)(data + STATE_HEADER + raw + battery),
    };
    return core->rtc.d.deserialize(&core->rtc.d, &rtc) && flush_battery(s);
}

static bool write_atomic(const char* path, const uint8_t* data, size_t size) {
    char temporary[PATH_MAX];
    int n = snprintf(temporary, sizeof(temporary), "%s.tmp.XXXXXX", path);
    if (n < 0 || (size_t)n >= sizeof(temporary)) return false;
    int fd = mkstemp(temporary);
    if (fd < 0) return false;
    size_t written = 0;
    while (written < size) {
        ssize_t count = write(fd, data + written, size - written);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        written += (size_t)count;
    }
    bool ok = written == size && fsync(fd) == 0;
    if (close(fd) != 0) ok = false;
    if (ok) ok = rename(temporary, path) == 0;
    if (!ok) unlink(temporary);
    return ok;
}

static uint8_t* read_state_file(const char* path, size_t* size) {
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return NULL;
    struct stat st;
    uint8_t* data = NULL;
    if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size >= STATE_HEADER && st.st_size <= MAX_STATE) {
        *size = (size_t)st.st_size;
        data = malloc(*size);
        size_t offset = 0;
        while (data && offset < *size) {
            ssize_t n = read(fd, data + offset, *size - offset);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) break;
            offset += (size_t)n;
        }
        if (offset != *size) {
            free(data);
            data = NULL;
        }
    }
    close(fd);
    return data;
}

JNIEXPORT jlong JNICALL JNI_METHOD(open)(JNIEnv* env, jobject self, jstring rom_path, jstring save_path) {
    (void)self;
    char* rom = copy_path(env, rom_path);
    if (!rom) return 0;
    char* save = copy_path(env, save_path);
    if (!save) { free(rom); return 0; }
    pthread_mutex_lock(&sessions_lock);
    struct Session* s = NULL;
    struct VFile* rom_file = NULL;
    struct VFile* save_file = NULL;
    jlong result = 0;
    struct stat st;
    if (stat(rom, &st) || !S_ISREG(st.st_mode) || st.st_size < 0xC0 || st.st_size > 0x2000000 ||
        same_file(rom, save) || next_id == INT64_MAX) goto done;
    /* Saves cannot alias another live core's ROM or save in either direction. */
    for (struct Session* other = sessions; other; other = other->next) {
        if (same_file(other->save_path, save) || same_file(other->rom_path, save) ||
            same_file(other->save_path, rom)) goto done;
    }
    rom_file = VFileOpen(rom, O_RDONLY);
    if (!rom_file) goto done;
    ssize_t source_size = rom_file->size(rom_file);
    uint8_t rom_sha256[32];
    if (source_size < 0xC0 || source_size > 0x2000000 ||
        !hash_rom(env, rom_file, (size_t)source_size, rom_sha256)) goto done;
    struct mCore* core = mCoreFindVF(rom_file);
    if (!core) goto done;
    if (!core->init(core)) { free(core); goto done; }
    mCoreInitConfig(core, NULL);
    s = calloc(1, sizeof(*s));
    if (!s) { mCoreConfigDeinit(&core->config); core->deinit(core); goto done; }
    s->core = core;
    s->rom_path = rom; rom = NULL;
    s->save_path = save; save = NULL;
    s->rom_size = (uint32_t)source_size;
    memcpy(s->rom_sha256, rom_sha256, sizeof(s->rom_sha256));
    int identity_length = snprintf(s->core_identity, sizeof(s->core_identity), "mGBA/%s;%s", projectVersion, MGBA_REVISION);
    if (identity_length < 0 || (size_t)identity_length >= sizeof(s->core_identity)) goto done;
    mCoreConfigSetDefaultIntValue(&core->config, "useBios", 0);
    mCoreConfigSetDefaultIntValue(&core->config, "skipBios", 1);
    mCoreConfigSetDefaultIntValue(&core->config, "volume", 0x100);
    mCoreConfigSetDefaultIntValue(&core->config, "threadedVideo", 0);
    mCoreLoadForeignConfig(core, &core->config);
    core->setVideoBuffer(core, s->video, 240);
    core->setAudioBufferSize(core, AUDIO_FRAMES);
    if (!core->loadROM(core, rom_file)) goto done;
    rom_file = NULL; /* On success the core owns the ROM VFile. */
    save_file = VFileOpen(s->save_path, O_RDWR | O_CREAT);
    if (!save_file || same_file(s->rom_path, s->save_path) || stat(s->save_path, &st) ||
        !S_ISREG(st.st_mode) || st.st_size > MAX_BATTERY) goto done;
    if (!core->loadSave(core, save_file)) goto done;
    save_file = NULL;
    core->reset(core);
    blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), 32768);
    blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), 32768);
    s->id = next_id++;
    s->next = sessions;
    sessions = s;
    result = s->id;
    s = NULL;
done:
    if (rom_file) rom_file->close(rom_file);
    if (save_file) save_file->close(save_file);
    if (s) destroy_session(s);
    free(rom);
    free(save);
    pthread_mutex_unlock(&sessions_lock);
    return result;
}

JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv* env, jobject self, jlong handle) {
    (void)self;
    pthread_mutex_lock(&sessions_lock);
    struct Session** link = &sessions;
    while (*link && (*link)->id != handle) link = &(*link)->next;
    if (*link) {
        struct Session* s = *link;
        *link = s->next;
        bool flushed = s->poisoned || flush_battery(s);
        destroy_session(s);
        if (!flushed) throw_exception(env, "java/io/IOException", "Could not flush branch battery save");
    }
    pthread_mutex_unlock(&sessions_lock);
}

JNIEXPORT void JNICALL JNI_METHOD(runFrame)(JNIEnv* env, jobject self, jlong handle, jint keys) {
    (void)self;
    pthread_mutex_lock(&sessions_lock);
    struct Session* s = find_session(env, handle);
    if (s) {
        /* The host drains once per frame. Drop any forgotten samples without
         * resetting the resampler's fractional clock or overflowing its buffer. */
        for (int i = 0; i < 2; ++i) {
            blip_t* channel = s->core->getAudioChannel(s->core, i);
            int pending = blip_samples_avail(channel);
            while (pending > 0) {
                int count = pending > AUDIO_FRAMES ? AUDIO_FRAMES : pending;
                int removed = blip_read_samples(channel, s->audio, count, 0);
                if (removed <= 0) break;
                pending -= removed;
            }
        }
        s->core->setKeys(s->core, (uint32_t)keys & 0x3FFu);
        s->core->runFrame(s->core);
        s->has_frame = true;
    }
    pthread_mutex_unlock(&sessions_lock);
}

JNIEXPORT jint JNICALL JNI_METHOD(releaseKeys)(JNIEnv* env, jobject self, jlong handle) {
    (void)self;
    pthread_mutex_lock(&sessions_lock);
    struct Session* s = find_session(env, handle);
    jint previous = 0;
    if (s) {
        previous = (jint)s->core->getKeys(s->core);
        s->core->setKeys(s->core, 0);
    }
    pthread_mutex_unlock(&sessions_lock);
    return previous;
}

JNIEXPORT jboolean JNICALL JNI_METHOD(copyFrame)(JNIEnv* env, jobject self, jlong handle, jintArray pixels) {
    (void)self;
    pthread_mutex_lock(&sessions_lock);
    struct Session* s = find_session(env, handle);
    bool copied = false;
    if (s && (!pixels || (*env)->GetArrayLength(env, pixels) < FRAME_PIXELS)) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Frame array requires 38400 pixels");
    } else if (s && s->has_frame) {
        for (size_t i = 0; i < FRAME_PIXELS; ++i) {
            uint32_t p = s->video[i];
            s->argb[i] = (jint)(0xFF000000u | ((p & 0xFFu) << 16) | (p & 0xFF00u) | ((p >> 16) & 0xFFu));
        }
        (*env)->SetIntArrayRegion(env, pixels, 0, FRAME_PIXELS, s->argb);
        copied = !(*env)->ExceptionCheck(env);
    }
    pthread_mutex_unlock(&sessions_lock);
    return copied;
}

JNIEXPORT jint JNICALL JNI_METHOD(drainAudio)(JNIEnv* env, jobject self, jlong handle, jshortArray samples) {
    (void)self;
    pthread_mutex_lock(&sessions_lock);
    struct Session* s = find_session(env, handle);
    int shorts = 0;
    if (s && !samples) {
        throw_exception(env, "java/lang/IllegalArgumentException", "Audio array is required");
    } else if (s) {
        blip_t* left = s->core->getAudioChannel(s->core, 0);
        blip_t* right = s->core->getAudioChannel(s->core, 1);
        int count = (*env)->GetArrayLength(env, samples) / 2;
        if (count > AUDIO_FRAMES) count = AUDIO_FRAMES;
        if (count > blip_samples_avail(left)) count = blip_samples_avail(left);
        if (count > blip_samples_avail(right)) count = blip_samples_avail(right);
        if (count > 0) {
            blip_read_samples(left, s->audio, count, 1);
            blip_read_samples(right, s->audio + 1, count, 1);
            shorts = count * 2;
            (*env)->SetShortArrayRegion(env, samples, 0, shorts, s->audio);
        }
    }
    pthread_mutex_unlock(&sessions_lock);
    return shorts;
}

JNIEXPORT jboolean JNICALL JNI_METHOD(saveState)(JNIEnv* env, jobject self, jlong handle, jstring path) {
    (void)self;
    char* file = copy_path(env, path);
    if (!file) return false;
    pthread_mutex_lock(&sessions_lock);
    struct Session* s = find_session(env, handle);
    bool ok = false;
    if (s && state_path_allowed(s, file)) {
        size_t size = 0;
        uint8_t* state = capture_state(s, &size);
        if (state) ok = flush_battery(s) && write_atomic(file, state, size);
        free(state);
    }
    pthread_mutex_unlock(&sessions_lock);
    free(file);
    return ok;
}

JNIEXPORT jboolean JNICALL JNI_METHOD(loadState)(JNIEnv* env, jobject self, jlong handle, jstring path) {
    (void)self;
    char* file = copy_path(env, path);
    if (!file) return false;
    pthread_mutex_lock(&sessions_lock);
    struct Session* s = find_session(env, handle);
    bool ok = false;
    if (s && state_path_allowed(s, file)) {
        size_t size = 0, backup_size = 0;
        uint8_t* state = read_state_file(file, &size);
        if (state && validate_state(s, state, size)) {
            uint8_t* backup = capture_state(s, &backup_size);
            if (backup) {
                ok = apply_state(s, state);
                if (!ok && !apply_state(s, backup)) {
                    s->poisoned = true;
                    throw_exception(env, "java/lang/IllegalStateException", "State restore and rollback failed; close this core");
                }
                s->has_frame = false;
                for (int i = 0; i < 2; ++i) blip_clear(s->core->getAudioChannel(s->core, i));
                free(backup);
            }
        }
        free(state);
    }
    pthread_mutex_unlock(&sessions_lock);
    free(file);
    return ok;
}

JNIEXPORT jbyteArray JNICALL JNI_METHOD(readMemory)(JNIEnv* env, jobject self, jlong handle, jlong address, jint size) {
    (void)self;
    pthread_mutex_lock(&sessions_lock);
    struct Session* s = find_session(env, handle);
    jbyteArray result = NULL;
    if (!s) goto done;
    if (address < 0 || address > UINT32_MAX || size < 0 || size > MAX_READ) goto invalid;
    uint32_t region = (uint32_t)address >> 24;
    if (region == REGION_CART0 + 1) region = REGION_CART0; /* The canonical ROM window spans 32 MiB. */
    if (region != REGION_BIOS && region != REGION_WORKING_RAM && region != REGION_WORKING_IRAM &&
        region != REGION_PALETTE_RAM && region != REGION_VRAM && region != REGION_OAM && region != REGION_CART0) goto invalid;
    size_t available = 0;
    const uint8_t* memory = s->core->getMemoryBlock(s->core, region, &available);
    if (region == REGION_CART0 && available > s->core->romSize(s->core)) available = s->core->romSize(s->core);
    uint64_t offset = (uint64_t)address - ((uint64_t)region << 24);
    if (!memory || offset > available || (uint64_t)size > available - offset) goto invalid;
    result = (*env)->NewByteArray(env, size);
    if (result && size) (*env)->SetByteArrayRegion(env, result, 0, size, (const jbyte*)(memory + offset));
    goto done;
invalid:
    throw_exception(env, "java/lang/IllegalArgumentException", "Read must remain within one canonical BIOS, RAM, video or ROM region and at most 1 MiB");
done:
    pthread_mutex_unlock(&sessions_lock);
    return result;
}

/* Fixed, side-effect-free copy of LCD registers. No emulated bus reads or writes.
 * The owner captures this beside RAM/VRAM/palette/OAM between the same two frames. */
JNIEXPORT jbyteArray JNICALL JNI_METHOD(readVideoRegisters)(JNIEnv* env, jobject self, jlong handle) {
    (void)self;
    pthread_mutex_lock(&sessions_lock);
    struct Session* s = find_session(env, handle);
    jbyteArray result = NULL;
    if (s) {
        const struct GBA* gba = s->core->board;
        result = (*env)->NewByteArray(env, 0x60);
        if (result) (*env)->SetByteArrayRegion(env, result, 0, 0x60, (const jbyte*)gba->memory.io);
    }
    pthread_mutex_unlock(&sessions_lock);
    return result;
}

/* Staged input is detached from Java before the core lock is taken. The commit
 * loop contains only comparisons and bounded copies: no JNI calls, allocations,
 * emulation steps or fallible operations can leave a partially applied patch. */
JNIEXPORT jboolean JNICALL JNI_METHOD(compareAndWriteMemory)(JNIEnv* env, jobject self, jlong handle,
        jlongArray addresses, jobjectArray expected, jobjectArray replacement) {
    (void)self;
    struct Patch {
        uint32_t address;
        size_t length;
        size_t offset;
        uint8_t* destination;
    } patches[MAX_PATCHES];
    jlong requested[MAX_PATCHES];
    uint8_t* staged = NULL;
    jclass byte_array_type = NULL;
    bool committed = false;
    size_t total = 0;

    if (!addresses || !expected || !replacement) goto invalid;
    jsize count = (*env)->GetArrayLength(env, addresses);
    if (count <= 0 || count > MAX_PATCHES || (*env)->GetArrayLength(env, expected) != count ||
        (*env)->GetArrayLength(env, replacement) != count) goto invalid;
    (*env)->GetLongArrayRegion(env, addresses, 0, count, requested);
    if ((*env)->ExceptionCheck(env)) goto done;
    byte_array_type = (*env)->FindClass(env, "[B");
    if (!byte_array_type) goto done;
    staged = malloc(MAX_PATCH_BYTES * 2);
    if (!staged) {
        throw_exception(env, "java/lang/OutOfMemoryError", "Cannot stage RAM transaction");
        goto done;
    }

    for (jsize i = 0; i < count; ++i) {
        if (requested[i] < 0 || requested[i] > UINT32_MAX) goto invalid;
        uint32_t address = (uint32_t)requested[i];
        uint32_t region = address >> 24;
        size_t capacity;
        if (region == REGION_WORKING_RAM) capacity = SIZE_WORKING_RAM;
        else if (region == REGION_WORKING_IRAM) capacity = SIZE_WORKING_IRAM;
        else goto invalid;

        jbyteArray before = (*env)->GetObjectArrayElement(env, expected, i);
        if ((*env)->ExceptionCheck(env)) goto done;
        jbyteArray after = (*env)->GetObjectArrayElement(env, replacement, i);
        if ((*env)->ExceptionCheck(env)) {
            if (before) (*env)->DeleteLocalRef(env, before);
            goto done;
        }
        bool valid = before && after && (*env)->IsInstanceOf(env, before, byte_array_type) &&
            (*env)->IsInstanceOf(env, after, byte_array_type);
        size_t length = valid ? (size_t)(*env)->GetArrayLength(env, before) : 0;
        size_t region_offset = address - (region << 24);
        valid = valid && length > 0 && length == (size_t)(*env)->GetArrayLength(env, after) &&
            length <= MAX_PATCH_BYTES - total && region_offset < capacity && length <= capacity - region_offset;
        for (jsize j = 0; valid && j < i; ++j) {
            valid = (uint64_t)address + length <= patches[j].address ||
                (uint64_t)patches[j].address + patches[j].length <= address;
        }
        if (valid) {
            patches[i] = (struct Patch){address, length, total, NULL};
            (*env)->GetByteArrayRegion(env, before, 0, (jsize)length, (jbyte*)(staged + total));
            if (!(*env)->ExceptionCheck(env)) {
                (*env)->GetByteArrayRegion(env, after, 0, (jsize)length, (jbyte*)(staged + MAX_PATCH_BYTES + total));
            }
            total += length;
        }
        if (before) (*env)->DeleteLocalRef(env, before);
        if (after) (*env)->DeleteLocalRef(env, after);
        if ((*env)->ExceptionCheck(env)) goto done;
        if (!valid) goto invalid;
    }

    pthread_mutex_lock(&sessions_lock);
    struct Session* s = find_session(env, handle);
    if (s) {
        bool matches = true;
        for (jsize i = 0; i < count; ++i) {
            uint32_t region = patches[i].address >> 24;
            size_t available = 0;
            uint8_t* memory = s->core->getMemoryBlock(s->core, region, &available);
            size_t offset = patches[i].address - (region << 24);
            if (!memory || offset > available || patches[i].length > available - offset) {
                throw_exception(env, "java/lang/IllegalStateException", "RAM transaction backing memory is unavailable");
                matches = false;
                break;
            }
            patches[i].destination = memory + offset;
            if (memcmp(patches[i].destination, staged + patches[i].offset, patches[i].length)) {
                matches = false;
                break;
            }
        }
        if (matches) {
            for (jsize i = 0; i < count; ++i) {
                const uint8_t* before = staged + patches[i].offset;
                const uint8_t* after = staged + MAX_PATCH_BYTES + patches[i].offset;
                // Equal bytes act as guards without touching the emulated memory.
                if (memcmp(before, after, patches[i].length)) {
                    memcpy(patches[i].destination, after, patches[i].length);
                }
            }
            committed = true;
        }
    }
    pthread_mutex_unlock(&sessions_lock);
    goto done;
invalid:
    throw_exception(env, "java/lang/IllegalArgumentException",
        "RAM transaction requires 1-64 nonoverlapping canonical EWRAM/IWRAM ranges, equal nonzero lengths and at most 16 KiB total");
done:
    free(staged);
    if (byte_array_type) (*env)->DeleteLocalRef(env, byte_array_type);
    return committed;
}

JNIEXPORT jstring JNICALL JNI_METHOD(coreVersion)(JNIEnv* env, jobject self) {
    (void)self;
    return (*env)->NewStringUTF(env, projectVersion);
}
