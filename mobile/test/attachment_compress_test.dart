import 'dart:typed_data';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:oa_employee/attachment_compress.dart';

void main() {
  test('小图与 PDF 保持字节和名称', () async {
    final small = img.encodePng(img.Image(width: 32, height: 32));
    final result = await compressAttachment('小图.png', small);
    final legacy = base64Decode(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jWTkAAAAASUVORK5CYII=',
    );
    expect((await compressAttachment('历史小图.png', legacy)).bytes, legacy);
    expect(result.name, '小图.png');
    expect(result.bytes, small);
    final pdf = Uint8List.fromList('%PDF-1.4 sample'.codeUnits);
    final prepared = await compressAttachment('证明.pdf', pdf);
    expect(prepared.bytes, pdf);
    expect(prepared.name, '证明.pdf');
  });
  test('大图在 isolate 压缩后可解码且长边不超过2560', () async {
    final image = img.Image(width: 4000, height: 3000);
    // Detailed repeatable pixels ensure the original exceeds the target encoding size.
    var seed = 17;
    for (final pixel in image) {
      seed = (seed * 1664525 + 1013904223) & 0xffffffff;
      pixel.setRgb(seed & 255, (seed >> 8) & 255, (seed >> 16) & 255);
    }
    final original = img.encodeJpg(image, quality: 98);
    expect(original.length, greaterThan(3 * 1024 * 1024));
    final result = await compressAttachment('照片.jpeg', original);
    expect(result.name, '照片.jpg');
    expect(result.bytes.length, lessThan(original.length));
    final decoded = img.decodeJpg(result.bytes)!;
    expect(decoded.width, 2560);
    expect(decoded.height, 1920);
  });
}
