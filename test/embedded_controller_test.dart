import 'package:flutter/services.dart';
import 'package:flutter_mapbox_navigation/flutter_mapbox_navigation.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const viewId = 7;
  const eventChannelName = 'flutter_mapbox_navigation/$viewId/events';
  const codec = StandardMethodCodec();

  setUp(() {
    // Accept listen/cancel on the event channel.
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMessageHandler(eventChannelName, (ByteData? message) async {
      return codec.encodeSuccessEnvelope(null);
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMessageHandler(eventChannelName, null);
  });

  Future<void> emit(Object? event) {
    return TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
      eventChannelName,
      codec.encodeSuccessEnvelope(event),
      (_) {},
    );
  }

  test('dispose before any route was built does not throw', () {
    final controller = MapBoxNavigationViewController(viewId, null);
    expect(controller.dispose, returnsNormally);
  });

  test('malformed events are skipped and the stream keeps delivering',
      () async {
    final events = <RouteEvent>[];
    final controller = MapBoxNavigationViewController(viewId, events.add);
    await controller.initialize();
    // Re-subscribing must not stack a second listener.
    await controller.initialize();

    await emit('{"eventType": "user_off_route", "data": }');
    await emit(42);
    await emit('{"eventType": "user_off_route", "data": {}}');
    await emit('{"eventType": "navigation_finished", "data": {}}');

    expect(events.map((e) => e.eventType), [
      MapBoxEvent.user_off_route,
      MapBoxEvent.navigation_finished,
    ]);
    controller.dispose();
  });
}
