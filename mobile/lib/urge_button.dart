import 'dart:async';

import 'package:flutter/material.dart';

import 'api.dart';
import 'ui.dart';

class UrgeButton extends StatefulWidget {
  final Api api;
  final Json detail;
  final String path;
  final Future<void> Function() onDone;
  const UrgeButton({
    super.key,
    required this.api,
    required this.detail,
    required this.path,
    required this.onDone,
  });
  @override
  State<UrgeButton> createState() => _UrgeButtonState();
}

class _UrgeButtonState extends State<UrgeButton> with Guard<UrgeButton> {
  Timer? timer;
  @override
  void initState() {
    super.initState();
    timer = Timer.periodic(const Duration(seconds: 30), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (widget.detail['canUrge'] != true) return const SizedBox.shrink();
    final available = DateTime.tryParse('${widget.detail['urgeAvailableAt']}');
    final cooling = available != null && available.isAfter(DateTime.now());
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        feedback,
        OutlinedButton(
          onPressed: busy || cooling
              ? null
              : () => run(() async {
                  final result = await widget.api.call(
                    '${widget.path}/urge',
                    method: 'POST',
                  ) as Json;
                  if (mounted && context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text('${result['message']}')),
                    );
                  }
                  await widget.onDone();
                }),
          child: Text(busy ? '提交中…' : '催办'),
        ),
        if (cooling)
          Text('可再次催办时间：${timeOf(widget.detail['urgeAvailableAt'])}'),
      ],
    );
  }
}
