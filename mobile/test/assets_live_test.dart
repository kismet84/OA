import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:oa_employee/api.dart';

void main() {
  final prefix = Platform.environment['OA_ASSET_PREFIX'];
  test(
    '真实Flutter API：领用、直属审批、发放、归还、消耗品和撤回',
    () async {
      final owner = Api(), manager = Api(), finance = Api();
      try {
        await owner.login(
          '${prefix}owner',
          'Asset@2026Changed',
          '资产员工验收',
          'test',
        );
        await manager.login(
          '${prefix}manager',
          'Asset@2026Changed',
          '资产上级验收',
          'test',
        );
        await finance.login(
          '${prefix}finance',
          'Asset@2026Changed',
          '资产行政验收',
          'test',
        );
        final catalog = await owner.call('/assets/catalog') as List;
        final category = catalog.firstWhere((r) => r['name'] == '$prefix电脑');
        final supplies = catalog.firstWhere((r) => r['name'] == '$prefix鼠标');
        final code = 'DART-${DateTime.now().microsecondsSinceEpoch}';
        final item = await finance.call(
          '/assets',
          method: 'POST',
          data: {
            'categoryId': category['id'],
            'code': code,
            'name': 'Dart验收电脑',
            'enabled': true,
          },
        );
        Future<int> apply({bool consumable = false}) async {
          final r = await owner.call(
            '/assets/requests',
            method: 'POST',
            data: {
              if (consumable)
                'consumableId': supplies['id']
              else
                'categoryId': category['id'],
              'quantity': 1,
              'purpose': 'Dart真实验收',
              'expectedDate': '2032-03-01',
            },
          );
          return r['id'] as int;
        }

        Future<void> approve(int id) async {
          final pending =
              await manager.call('/messages?category=pending') as List;
          expect(
            pending.any((r) => r['item_type'] == 'ASSET' && r['id'] == id),
            isTrue,
          );
          await manager.call(
            '/assets/requests/$id/approve',
            method: 'POST',
            data: {'approved': true},
          );
        }

        final id = await apply();
        expect(
          (await owner.call('/assets/requests/$id'))['needs_return'],
          isTrue,
        );
        await approve(id);
        await finance.call(
          '/assets/requests/$id/issue',
          method: 'POST',
          data: {
            'assetIds': [item['id']],
          },
        );
        expect((await owner.call('/assets/requests/$id'))['status'], 'IN_USE');
        final holdings = await owner.call('/assets/mine') as List;
        expect(
          holdings.any((r) => r['code'] == code && r['returned_at'] == null),
          isTrue,
        );
        await owner.call(
          '/assets/requests/$id/return',
          method: 'POST',
          data: {'note': '员工交回'},
        );
        expect(
          (await owner.call('/assets/requests/$id'))['return_requested'],
          isTrue,
        );
        await finance.call(
          '/assets/requests/$id/confirm-return',
          method: 'POST',
          data: {'note': '已收回'},
        );
        expect(
          (await owner.call('/assets/requests/$id'))['status'],
          'RETURNED',
        );
        final ledger =
            await finance.call('/assets?categoryId=${category['id']}') as List;
        expect(
          ledger.firstWhere((r) => r['id'] == item['id'])['status'],
          'AVAILABLE',
        );
        final notices =
            await owner.call('/messages?category=notifications') as List;
        expect(notices.any((r) => r['asset_request_id'] == id), isTrue);
        final before = supplies['available'] as int;
        final consumable = await apply(consumable: true);
        await approve(consumable);
        await finance.call(
          '/assets/requests/$consumable/issue',
          method: 'POST',
          data: {},
        );
        final after = await owner.call('/assets/catalog') as List;
        expect(
          after.firstWhere(
            (r) => r['kind'] == 'CONSUMABLE' && r['id'] == supplies['id'],
          )['available'],
          before - 1,
        );
        expect(
          (await owner.call('/assets/requests/$consumable'))['needs_return'],
          isFalse,
        );
        final withdrawn = await apply();
        await owner.call(
          '/assets/requests/$withdrawn/withdraw',
          method: 'POST',
        );
        expect(
          (await owner.call('/assets/requests/$withdrawn'))['status'],
          'WITHDRAWN',
        );
      } finally {
        owner.client.close();
        manager.client.close();
        finance.client.close();
      }
    },
    skip: prefix == null
        ? '先运行verify-phase3-assets.py，再设置OA_ASSET_PREFIX'
        : false,
  );
}
