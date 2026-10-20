import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_mapbox_navigation/src/embedded/controller.dart';
import 'package:flutter_mapbox_navigation/src/models/models.dart';

/// Callback method for when the navigation view is ready to be used.
///
/// Pass to MapBoxNavigationView.onMapCreated to receive a
/// [MapBoxNavigationViewController] when the
/// map is created.
typedef OnNavigationViewCreatedCallBack = void Function(
  MapBoxNavigationViewController controller,
);

///Embeddable Navigation View.
class MapBoxNavigationView extends StatelessWidget {
  ///Embeddable Navigation View Constructor
  const MapBoxNavigationView({
    super.key,
    this.options,
    this.onCreated,
    this.onRouteEvent,
  });

  static const StandardMessageCodec _decoder = StandardMessageCodec();

  /// MapBox options
  final MapBoxOptions? options;

  /// Callback when view is created
  final OnNavigationViewCreatedCallBack? onCreated;

  /// Value setter for RouteEvents
  final ValueSetter<RouteEvent>? onRouteEvent;

  /// View name
  static const String viewType = 'FlutterMapboxNavigationView';

  @override
  Widget build(BuildContext context) {
    if (Platform.isAndroid) {
      // using Hybrid Composition
      return PlatformViewLink(
        viewType: viewType,
        surfaceFactory: (context, controller) {
          return AndroidViewSurface(
            controller: controller as AndroidViewController,
            // The map takes every touch that lands on it. With none here a
            // tap never reached it — a drag did, a tap was lost in Flutter's
            // gesture arena — so a tap on another way's tag or line did
            // nothing (2026-10-19). Flutter widgets drawn over the map are
            // hit first and keep their own taps.
            gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{
              Factory<OneSequenceGestureRecognizer>(EagerGestureRecognizer.new),
            },
            hitTestBehavior: PlatformViewHitTestBehavior.opaque,
          );
        },
        onCreatePlatformView: (params) {
          return PlatformViewsService.initExpensiveAndroidView(
            id: params.id,
            viewType: viewType,
            layoutDirection: TextDirection.ltr,
            creationParams: options!.toMap(),
            creationParamsCodec: const StandardMessageCodec(),
            onFocus: () {
              params.onFocusChanged(true);
            },
          )
            ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
            ..addOnPlatformViewCreatedListener(_onPlatformViewCreated)
            ..create();
        },
      );
    } else if (Platform.isIOS) {
      return UiKitView(
        viewType: 'FlutterMapboxNavigationView',
        onPlatformViewCreated: _onPlatformViewCreated,
        creationParams: options!.toMap(),
        creationParamsCodec: _decoder,
      );
    } else {
      return Container();
    }
  }

  void _onPlatformViewCreated(int id) {
    if (onCreated == null) {
      return;
    }
    onCreated!(MapBoxNavigationViewController(id, onRouteEvent));
  }
}
