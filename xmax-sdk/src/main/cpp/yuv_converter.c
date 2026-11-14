//
//  yuv_converter.c
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/**
 * Copies tightly packed RGBA into independently owned, bilinearly resized storage.
 *
 * @param env The JNI environment.
 * @param receiver The Kotlin converter instance.
 * @param source The original RGBA byte array.
 * @param source_width The original width, in pixels.
 * @param source_height The original height, in pixels.
 * @param output The destination RGBA byte array.
 * @param width The destination width, in pixels.
 * @param height The destination height, in pixels.
 * @return JNI_TRUE on success, JNI_FALSE for invalid storage or allocation failure.
 */
JNIEXPORT jboolean JNICALL
Java_ai_xmax_sdk_media_video_NativeYuv420Converter_resizeRgbaNative(
    JNIEnv *env,
    jobject receiver,
    jbyteArray source,
    jint source_width,
    jint source_height,
    jbyteArray output,
    jint width,
    jint height
) {
    (void) receiver;
    if (source == NULL || output == NULL || source_width <= 0 || source_height <= 0 || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    if ((int64_t) source_width * source_height > INT32_MAX / 4 ||
        (int64_t) width * height > INT32_MAX / 4 || (*env)->IsSameObject(env, source, output)) {
        return JNI_FALSE;
    }
    if ((int64_t) source_width * source_height * 4 != (*env)->GetArrayLength(env, source) ||
        (int64_t) width * height * 4 != (*env)->GetArrayLength(env, output)) {
        return JNI_FALSE;
    }

    jbyte *input = (*env)->GetByteArrayElements(env, source, NULL);
    if (input == NULL) return JNI_FALSE;
    jbyte *destination = (*env)->GetByteArrayElements(env, output, NULL);
    if (destination == NULL) {
        (*env)->ReleaseByteArrayElements(env, source, input, JNI_ABORT);
        return JNI_FALSE;
    }

    for (jint row = 0; row < height; ++row) {
        double y = (row + 0.5) * source_height / height - 0.5;
        if (y < 0) y = 0;
        if (y > source_height - 1) y = source_height - 1;
        const jint top = (jint) y;
        const jint bottom = top + 1 < source_height ? top + 1 : top;
        const double fy = y - top;
        for (jint column = 0; column < width; ++column) {
            double x = (column + 0.5) * source_width / width - 0.5;
            if (x < 0) x = 0;
            if (x > source_width - 1) x = source_width - 1;
            const jint left = (jint) x;
            const jint right = left + 1 < source_width ? left + 1 : left;
            const double fx = x - left;
            for (jint channel = 0; channel < 4; ++channel) {
                const double a = (uint8_t) input[(top * source_width + left) * 4 + channel];
                const double b = (uint8_t) input[(top * source_width + right) * 4 + channel];
                const double c = (uint8_t) input[(bottom * source_width + left) * 4 + channel];
                const double d = (uint8_t) input[(bottom * source_width + right) * 4 + channel];
                const double upper = a + (b - a) * fx;
                const double lower = c + (d - c) * fx;
                destination[(row * width + column) * 4 + channel] = (jbyte) (uint8_t) (upper + (lower - upper) * fy + 0.5);
            }
        }
    }

    (*env)->ReleaseByteArrayElements(env, output, destination, 0);
    (*env)->ReleaseByteArrayElements(env, source, input, JNI_ABORT);
    return JNI_TRUE;
}

/**
 * A pixel plane and its safely accessible memory layout.
 */
typedef struct {

    /**
     * The pixel plane's starting memory address.
     */
    const uint8_t *data;

    /**
     * The accessible buffer capacity, in bytes.
     */
    jlong capacity;

    /**
     * The valid data's starting byte offset.
     */
    jint offset;

    /**
     * The byte distance between consecutive row starts.
     */
    jint row_stride;

    /**
     * The pixel stride, in bytes.
     */
    jint pixel_stride;
} Plane;

/**
 * Clamps a color channel to the valid eight-bit range.
 *
 * @param value The color channel value to clamp.
 * @return A channel value from 0 to 255.
 */
static jint clamp_color(
    jint value
) {
    if (value < 0) return 0;
    if (value > 255) return 255;
    return value;
}

/**
 * Checks whether the furthest requested plane coordinate is safely accessible.
 *
 * @param plane The pixel plane to validate.
 * @param maximum_x The furthest horizontal sample coordinate, in pixels.
 * @param maximum_y The furthest vertical sample coordinate, in pixels.
 * @return JNI_TRUE for valid, accessible data, strides, and coordinates; JNI_FALSE otherwise.
 */
static jboolean valid_plane(
    const Plane *plane,
    jint maximum_x,
    jint maximum_y
) {
    if (plane->data == NULL || plane->capacity <= 0 || plane->offset < 0 ||
        plane->row_stride <= 0 || plane->pixel_stride <= 0 ||
        maximum_x < 0 || maximum_y < 0) {
        return JNI_FALSE;
    }

    const jlong last = (jlong) plane->offset +
        (jlong) maximum_y * plane->row_stride +
        (jlong) maximum_x * plane->pixel_stride;
    return last < plane->capacity ? JNI_TRUE : JNI_FALSE;
}

/**
 * Copies a validated plane with pixel stride 1 or 2 to tightly packed output.
 *
 * @param plane The source plane to copy.
 * @param width The number of samples in each row.
 * @param height The number of rows.
 * @param output The destination plane.
 */
static void copy_plane(
    const Plane *plane,
    jint width,
    jint height,
    uint8_t *restrict output
) {
    const uint8_t *input = plane->data + plane->offset;
    if (plane->pixel_stride == 1) {
        if (plane->row_stride == width) {
            memcpy(output, input, (size_t) width * height);
            return;
        }

        for (jint y = 0; y < height; ++y) {
            memcpy(output + (size_t) y * width, input + (size_t) y * plane->row_stride, width);
        }
        return;
    }

    for (jint y = 0; y < height; ++y) {
        const uint8_t *src_row = input + (size_t) y * plane->row_stride;
        uint8_t *dst_row = output + (size_t) y * width;
        for (jint x = 0; x < width; ++x) {
            dst_row[x] = src_row[(size_t) x * 2];
        }
    }
}

/**
 * Maps display coordinates to the source before cropping and rotation.
 *
 * @param display_x The horizontal display coordinate.
 * @param display_y The vertical display coordinate.
 * @param crop_left The left crop edge.
 * @param crop_top The top crop edge.
 * @param crop_width The crop width.
 * @param crop_height The crop height.
 * @param rotation The clockwise rotation: 0, 90, 180, or 270 degrees.
 * @param source_x Receives the horizontal source coordinate.
 * @param source_y Receives the vertical source coordinate.
 */
static inline void source_coordinate(
    jint display_x,
    jint display_y,
    jint crop_left,
    jint crop_top,
    jint crop_width,
    jint crop_height,
    jint rotation,
    jint *source_x,
    jint *source_y
) {
    switch (rotation) {
        case 90:
            *source_x = crop_left + display_y;
            *source_y = crop_top + crop_height - 1 - display_x;
            break;
        case 180:
            *source_x = crop_left + crop_width - 1 - display_x;
            *source_y = crop_top + crop_height - 1 - display_y;
            break;
        case 270:
            *source_x = crop_left + crop_width - 1 - display_y;
            *source_y = crop_top + display_x;
            break;
        default:
            *source_x = crop_left + display_x;
            *source_y = crop_top + display_y;
            break;
    }
}

/**
 * Crops, rotates, and aspect-fills a direct-buffer YUV420 image into I420 output dimensions.
 *
 * @param env The current thread's JNI environment.
 * @param instance The calling Kotlin singleton, unused by this function.
 * @param y_buffer The direct Y-plane buffer.
 * @param y_offset The valid Y-plane byte offset.
 * @param y_row_stride The Y-plane row stride, in bytes.
 * @param y_pixel_stride The Y-plane pixel stride, in bytes.
 * @param u_buffer The direct U-plane buffer.
 * @param u_offset The valid U-plane byte offset.
 * @param u_row_stride The U-plane row stride, in bytes.
 * @param u_pixel_stride The U-plane pixel stride, in bytes.
 * @param v_buffer The direct V-plane buffer.
 * @param v_offset The valid V-plane byte offset.
 * @param v_row_stride The V-plane row stride, in bytes.
 * @param v_pixel_stride The V-plane pixel stride, in bytes.
 * @param source_width The source width, in pixels.
 * @param source_height The source height, in pixels.
 * @param crop_left The left crop edge, in pixels.
 * @param crop_top The top crop edge, in pixels.
 * @param crop_width The crop width, in pixels.
 * @param crop_height The crop height, in pixels.
 * @param output_width The output width; must be positive and even.
 * @param output_height The output height; must be positive and even.
 * @param rotation The clockwise rotation: 0, 90, 180, or 270 degrees.
 * @param mirrored Whether to mirror across the output width after rotation.
 * @param output The output array containing Y, U, and V consecutively.
 * @return JNI_TRUE on success; JNI_FALSE for invalid input, insufficient memory, or inaccessible buffers.
 */
JNIEXPORT jboolean JNICALL
Java_ai_xmax_sdk_media_video_NativeYuv420Converter_convertNative(
    JNIEnv *env,
    jobject instance,
    jobject y_buffer,
    jint y_offset,
    jint y_row_stride,
    jint y_pixel_stride,
    jobject u_buffer,
    jint u_offset,
    jint u_row_stride,
    jint u_pixel_stride,
    jobject v_buffer,
    jint v_offset,
    jint v_row_stride,
    jint v_pixel_stride,
    jint source_width,
    jint source_height,
    jint crop_left,
    jint crop_top,
    jint crop_width,
    jint crop_height,
    jint output_width,
    jint output_height,
    jint rotation,
    jboolean mirrored,
    jbyteArray output
) {
    (void) instance;
    if (source_width <= 0 || source_height <= 0 || crop_left < 0 || crop_top < 0 ||
        crop_width <= 0 || crop_height <= 0 ||
        crop_left + crop_width > source_width || crop_top + crop_height > source_height ||
        output_width <= 0 || output_height <= 0 ||
        (output_width & 1) != 0 || (output_height & 1) != 0 ||
        (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) ||
        output == NULL) {
        return JNI_FALSE;
    }

    const jint chroma_width = output_width / 2;
    const jint chroma_height = output_height / 2;
    const int64_t luma_size = (int64_t) output_width * output_height;
    const int64_t chroma_size = (int64_t) chroma_width * chroma_height;
    if ((*env)->GetArrayLength(env, output) < luma_size + 2 * chroma_size) {
        return JNI_FALSE;
    }

    Plane y_plane = {
        .data = (*env)->GetDirectBufferAddress(env, y_buffer),
        .capacity = (*env)->GetDirectBufferCapacity(env, y_buffer),
        .offset = y_offset,
        .row_stride = y_row_stride,
        .pixel_stride = y_pixel_stride,
    };
    Plane u_plane = {
        .data = (*env)->GetDirectBufferAddress(env, u_buffer),
        .capacity = (*env)->GetDirectBufferCapacity(env, u_buffer),
        .offset = u_offset,
        .row_stride = u_row_stride,
        .pixel_stride = u_pixel_stride,
    };
    Plane v_plane = {
        .data = (*env)->GetDirectBufferAddress(env, v_buffer),
        .capacity = (*env)->GetDirectBufferCapacity(env, v_buffer),
        .offset = v_offset,
        .row_stride = v_row_stride,
        .pixel_stride = v_pixel_stride,
    };
    const jint maximum_x = crop_left + crop_width - 1;
    const jint maximum_y = crop_top + crop_height - 1;
    if (!valid_plane(&y_plane, maximum_x, maximum_y) ||
        !valid_plane(&u_plane, maximum_x / 2, maximum_y / 2) ||
        !valid_plane(&v_plane, maximum_x / 2, maximum_y / 2)) {
        return JNI_FALSE;
    }

    if (rotation == 0 && !mirrored && crop_left == 0 && crop_top == 0 &&
        crop_width == source_width && crop_height == source_height &&
        output_width == source_width && output_height == source_height &&
        y_plane.pixel_stride == 1 && u_plane.pixel_stride <= 2 && v_plane.pixel_stride <= 2) {
        jbyte *bytes = (*env)->GetByteArrayElements(env, output, NULL);
        if (bytes == NULL) return JNI_FALSE;

        uint8_t *pixels = (uint8_t *) bytes;
        copy_plane(&y_plane, output_width, output_height, pixels);
        copy_plane(&u_plane, chroma_width, chroma_height, pixels + luma_size);
        copy_plane(&v_plane, chroma_width, chroma_height, pixels + luma_size + chroma_size);
        (*env)->ReleaseByteArrayElements(env, output, bytes, 0);
        return JNI_TRUE;
    }

    jint *target_x = malloc((size_t) output_width * sizeof(jint));
    jint *target_y = malloc((size_t) output_height * sizeof(jint));
    if (target_x == NULL || target_y == NULL) {
        free(target_x);
        free(target_y);
        return JNI_FALSE;
    }

    const jboolean swaps_dimensions = rotation == 90 || rotation == 270;
    const jint display_width = swaps_dimensions ? crop_height : crop_width;
    const jint display_height = swaps_dimensions ? crop_width : crop_height;
    const double width_scale = (double) output_width / display_width;
    const double height_scale = (double) output_height / display_height;
    const double scale = width_scale > height_scale ? width_scale : height_scale;
    const double display_left = (display_width - output_width / scale) / 2.0;
    const double display_top = (display_height - output_height / scale) / 2.0;

    for (jint x = 0; x < output_width; ++x) {
        const jint column = mirrored ? output_width - 1 - x : x;
        jint mapped = (jint) (display_left + (column + 0.5) / scale);
        if (mapped < 0) mapped = 0;
        if (mapped >= display_width) mapped = display_width - 1;
        target_x[x] = mapped;
    }
    for (jint y = 0; y < output_height; ++y) {
        jint mapped = (jint) (display_top + (y + 0.5) / scale);
        if (mapped < 0) mapped = 0;
        if (mapped >= display_height) mapped = display_height - 1;
        target_y[y] = mapped;
    }

    jbyte *y_result = (*env)->GetByteArrayElements(env, output, NULL);
    if (y_result == NULL) {
        free(target_x);
        free(target_y);
        return JNI_FALSE;
    }
    jbyte *u_result = y_result + luma_size;
    jbyte *v_result = u_result + chroma_size;

    jint output_index = 0;
    for (jint y = 0; y < output_height; ++y) {
        const jint display_y = target_y[y];
        for (jint x = 0; x < output_width; ++x) {
            jint source_x;
            jint source_y;
            source_coordinate(
                target_x[x], display_y, crop_left, crop_top, crop_width, crop_height,
                rotation, &source_x, &source_y
            );
            y_result[output_index++] = (jbyte) y_plane.data[
                y_plane.offset + source_y * y_plane.row_stride + source_x * y_plane.pixel_stride
            ];
        }
    }

    output_index = 0;
    for (jint y = 0; y < chroma_height; ++y) {
        const jint luma_y = y * 2 + 1 < output_height ? y * 2 + 1 : output_height - 1;
        const jint display_y = target_y[luma_y];
        for (jint x = 0; x < chroma_width; ++x) {
            const jint luma_x = x * 2 + 1 < output_width ? x * 2 + 1 : output_width - 1;
            jint source_x;
            jint source_y;
            source_coordinate(
                target_x[luma_x], display_y, crop_left, crop_top, crop_width, crop_height,
                rotation, &source_x, &source_y
            );
            source_x /= 2;
            source_y /= 2;
            u_result[output_index] = (jbyte) u_plane.data[
                u_plane.offset + source_y * u_plane.row_stride + source_x * u_plane.pixel_stride
            ];
            v_result[output_index] = (jbyte) v_plane.data[
                v_plane.offset + source_y * v_plane.row_stride + source_x * v_plane.pixel_stride
            ];
            ++output_index;
        }
    }

    (*env)->ReleaseByteArrayElements(env, output, y_result, 0);
    free(target_x);
    free(target_y);
    return JNI_TRUE;
}

/**
 * Converts I420 pixels to ARGB for Android Bitmap output.
 *
 * @param env The current thread's JNI environment.
 * @param instance The calling Kotlin singleton, unused by this function.
 * @param y_data The array containing the Y luma plane.
 * @param y_offset The first byte of the Y plane.
 * @param y_stride The Y-plane row stride, in bytes.
 * @param u_data The array containing the U chroma plane.
 * @param u_offset The first byte of the U plane.
 * @param u_stride The U-plane row stride, in bytes.
 * @param v_data The array containing the V chroma plane.
 * @param v_offset The first byte of the V plane.
 * @param v_stride The V-plane row stride, in bytes.
 * @param width The image width, in pixels.
 * @param height The image height, in pixels.
 * @param output_pixels Receives ARGB pixels and must hold at least width times height elements.
 * @return JNI_TRUE on success; JNI_FALSE for invalid input or inaccessible arrays.
 */
JNIEXPORT jboolean JNICALL
Java_ai_xmax_sdk_media_video_NativeYuv420Converter_convertI420ToArgbNative(
    JNIEnv *env,
    jobject instance,
    jbyteArray y_data,
    jint y_offset,
    jint y_stride,
    jbyteArray u_data,
    jint u_offset,
    jint u_stride,
    jbyteArray v_data,
    jint v_offset,
    jint v_stride,
    jint width,
    jint height,
    jintArray output_pixels
) {
    (void) instance;
    if (y_data == NULL || u_data == NULL || v_data == NULL || output_pixels == NULL ||
        y_offset < 0 || u_offset < 0 || v_offset < 0 || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }

    const jint chroma_width = width / 2 + width % 2;
    const jint chroma_height = height / 2 + height % 2;
    if (y_stride < width || u_stride < chroma_width || v_stride < chroma_width ||
        (*env)->GetArrayLength(env, y_data) < y_offset + (int64_t) (height - 1) * y_stride + width ||
        (*env)->GetArrayLength(env, u_data) < u_offset + (int64_t) (chroma_height - 1) * u_stride + chroma_width ||
        (*env)->GetArrayLength(env, v_data) < v_offset + (int64_t) (chroma_height - 1) * v_stride + chroma_width ||
        (*env)->GetArrayLength(env, output_pixels) < (int64_t) width * height) {
        return JNI_FALSE;
    }

    // Acquire each backing array once so packed planes share a single JNI array access.
    const jboolean u_shares_y = (*env)->IsSameObject(env, u_data, y_data);
    const jboolean v_shares_y = (*env)->IsSameObject(env, v_data, y_data);
    const jboolean v_shares_u = !v_shares_y && (*env)->IsSameObject(env, v_data, u_data);
    jbyte *y = (*env)->GetByteArrayElements(env, y_data, NULL);
    if (y == NULL) return JNI_FALSE;
    jbyte *u = u_shares_y ? y : (*env)->GetByteArrayElements(env, u_data, NULL);
    jbyte *v = NULL;
    jboolean converted = JNI_FALSE;
    if (u == NULL) goto release_arrays;
    if (v_shares_y) {
        v = y;
    } else if (v_shares_u) {
        v = u;
    } else {
        v = (*env)->GetByteArrayElements(env, v_data, NULL);
    }
    if (v == NULL) goto release_arrays;
    jint *pixels = (*env)->GetIntArrayElements(env, output_pixels, NULL);
    if (pixels == NULL) goto release_arrays;

    jint output_index = 0;
    for (jint row = 0; row < height; ++row) {
        const jint y_row = y_offset + row * y_stride;
        const jint chroma_u_row = u_offset + (row / 2) * u_stride;
        const jint chroma_v_row = v_offset + (row / 2) * v_stride;
        for (jint column = 0; column < width; ++column) {
            const jint luma = ((uint8_t) y[y_row + column]) - 16;
            const jint chroma_u = ((uint8_t) u[chroma_u_row + column / 2]) - 128;
            const jint chroma_v = ((uint8_t) v[chroma_v_row + column / 2]) - 128;
            const jint normalized_luma = luma > 0 ? luma : 0;
            const jint red = clamp_color((298 * normalized_luma + 409 * chroma_v + 128) >> 8);
            const jint green = clamp_color(
                (298 * normalized_luma - 100 * chroma_u - 208 * chroma_v + 128) >> 8
            );
            const jint blue = clamp_color((298 * normalized_luma + 516 * chroma_u + 128) >> 8);
            pixels[output_index++] = (jint) (
                0xFF000000u | ((uint32_t) red << 16) | ((uint32_t) green << 8) | (uint32_t) blue
            );
        }
    }

    (*env)->ReleaseIntArrayElements(env, output_pixels, pixels, 0);
    converted = JNI_TRUE;

release_arrays:
    if (v != NULL && !v_shares_y && !v_shares_u) (*env)->ReleaseByteArrayElements(env, v_data, v, JNI_ABORT);
    if (u != NULL && !u_shares_y) (*env)->ReleaseByteArrayElements(env, u_data, u, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, y_data, y, JNI_ABORT);
    return converted;
}
