import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:oa_employee/api.dart';

void main() {
  final prefix = Platform.environment['OA_CONTRACT_PREFIX'];
  test(
    '真实Flutter API：登记、驳回重提、三级审批、盖章归档、附件和终止',
    () async {
      final owner = Api(), manager = Api(), head = Api(), finance = Api();
      final clients = [owner, manager, head, finance];
      try {
        for (final entry in {
          'owner': owner,
          'manager': manager,
          'head': head,
          'finance': finance,
        }.entries) {
          await entry.value.login(
            '$prefix${entry.key}',
            'Asset@2026Changed',
            '合同真实接口验收',
            'test',
          );
        }
        final body = <String, dynamic>{
          'name': 'Flutter真实合同${DateTime.now().microsecondsSinceEpoch}',
          'type': 'SERVICE',
          'counterparty': prefix,
          'amount': 60000,
          'signedDate': '2032-01-01',
          'effectiveDate': '2032-01-01',
        };
        final id = (await owner.call(
          '/contracts',
          method: 'POST',
          data: body,
        ))['id'];
        Future<void> act(Api api, String action, [Json? data]) async {
          await api.call('/contracts/$id/$action', method: 'POST', data: data);
        }

        await act(owner, 'submit');
        final pending =
            await manager.call('/messages?category=pending') as List;
        expect(
          pending.any((r) => r['item_type'] == 'CONTRACT' && r['id'] == id),
          isTrue,
        );
        await act(manager, 'approve', {'approved': false, 'note': '补全履约说明'});
        expect((await owner.call('/contracts/$id'))['status'], 'REJECTED');
        await owner.call(
          '/contracts/$id',
          method: 'PUT',
          data: {...body, 'performance': '按月履约'},
        );
        await act(owner, 'submit');
        for (final approver in [manager, head, finance]) {
          expect((await approver.call('/contracts/$id'))['canApprove'], isTrue);
          await act(approver, 'approve', {'approved': true});
        }
        expect((await owner.call('/contracts/$id'))['status'], 'TO_ARCHIVE');
        final bytes = Uint8List.fromList(
          base64Decode(
            'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a2ioAAAAASUVORK5CYII=',
          ),
        );
        final file = await owner.upload('盖章版.png', bytes);
        await act(owner, 'archive', {
          'attachmentIds': [file],
        });
        final archived = await owner.call('/contracts/$id');
        expect(archived['status'], 'ACTIVE');
        expect((archived['files'] as List).single['sealed'], isTrue);
        expect(
          await owner.documentBytes('/contracts/$id/files/$file?preview=true'),
          bytes,
        );
        expect(await owner.documentBytes('/contracts/$id/files/$file'), bytes);
        final notices =
            await owner.call('/messages?category=notifications') as List;
        expect(notices.any((n) => n['contract_id'] == id), isTrue);
        await act(owner, 'terminate', {'reason': '真实接口验收结束'});
        expect((await owner.call('/contracts/$id'))['status'], 'TERMINATED');
      } finally {
        for (final api in clients) {
          api.client.close();
        }
      }
    },
    skip: prefix == null
        ? '先运行verify-phase3-contracts.py，再设置OA_CONTRACT_PREFIX'
        : false,
  );
}
