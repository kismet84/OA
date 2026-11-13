import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:image/image.dart' as img;

class PreparedAttachment {
  final String name;
  final Uint8List bytes;
  const PreparedAttachment(this.name, this.bytes);
}

// Runs in a background isolate on Android. Small images and PDFs retain their bytes.
Future<PreparedAttachment> compressAttachment(
  String name,
  Uint8List bytes,
) async {
  final result = await compute(compressAttachmentBytes, <String, dynamic>{
    'name': name,
    'bytes': bytes,
  });
  return PreparedAttachment(
    result['name'] as String,
    result['bytes'] as Uint8List,
  );
}

Map<String, dynamic> compressAttachmentBytes(Map<String, dynamic> input) {
  final name = input['name'] as String, bytes = input['bytes'] as Uint8List;
  final original = <String, dynamic>{'name': name, 'bytes': bytes};
  final jpeg =
      bytes.length > 3 && bytes[0] == 255 && bytes[1] == 216 && bytes[2] == 255;
  final png =
      bytes.length > 8 &&
      bytes[0] == 137 &&
      bytes[1] == 80 &&
      bytes[2] == 78 &&
      bytes[3] == 71;
  if (!jpeg && !png) return original;
  if (png && bytes.length >= 24 && bytes.length <= 1.5 * 1024 * 1024) {
    final header = bytes.buffer.asByteData(
      bytes.offsetInBytes,
      bytes.lengthInBytes,
    );
    final width = header.getUint32(16), height = header.getUint32(20);
    if (width > 0 && height > 0 && math.max(width, height) <= 2560) {
      return original;
    }
  }
  img.Image? decoded;
  try {
    decoded = img.decodeImage(bytes);
  } catch (_) {
    throw const FormatException('图片读取失败，请重新选择有效的 JPEG 或 PNG');
  }
  if (decoded == null) {
    throw const FormatException('图片读取失败，请重新选择有效的 JPEG 或 PNG');
  }
  final longest = math.max(decoded.width, decoded.height);
  if (bytes.length <= 1.5 * 1024 * 1024 && longest <= 2560) return original;
  var image = img.bakeOrientation(decoded);
  if (math.max(image.width, image.height) > 2560) {
    image = img.copyResize(
      image,
      width: image.width >= image.height ? 2560 : null,
      height: image.height > image.width ? 2560 : null,
    );
  }
  // JPEG has no alpha: use a white background, consistent with the employee web app.
  final white = img.Image(
    width: image.width,
    height: image.height,
    numChannels: 3,
  );
  img.fill(white, color: img.ColorRgb8(255, 255, 255));
  img.compositeImage(white, image);
  var encoded = img.encodeJpg(white, quality: 82);
  for (final quality in [74, 66, 60]) {
    if (encoded.length <= 2 * 1024 * 1024) break;
    encoded = img.encodeJpg(white, quality: quality);
  }
  if (encoded.length >= bytes.length) return original;
  return {
    'name': '${name.replaceFirst(RegExp(r'\.[^.]+$'), '')}.jpg',
    'bytes': encoded,
  };
}
