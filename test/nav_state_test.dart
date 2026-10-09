import 'dart:convert';

import 'package:flutter_mapbox_navigation/flutter_mapbox_navigation.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const full = <String, dynamic>{
    'maneuver': {
      'type': 'turn',
      'modifier': 'right',
      'text': 'Adelaide St W',
      'secondaryText': 'Toward Spadina',
    },
    'distanceToManeuver': 350.4,
    'then': {'type': 'arrive', 'modifier': 'left', 'text': 'Your stop'},
    'lanes': [
      {
        'indications': ['left'],
        'valid': false,
      },
      {
        'indications': ['straight', 'right'],
        'valid': true,
        'active': 'right',
      },
    ],
    'speedLimit': 40,
    'speedLimitUnit': 'km/h',
    'distanceRemaining': 2400,
    'durationRemaining': 480.5,
    'etaEpochMs': 1760000000000,
    'offRoute': false,
    'rerouting': true,
    'fasterRoute': {'savingSeconds': 240},
    'arrived': false,
    'latitude': 43.65,
    'longitude': -79.39,
    'bearing': 71.5,
    'speed': 8.2,
  };

  group('NavState', () {
    test('parses every field from a map (Android)', () {
      final state = NavState.tryParse(full)!;
      expect(state.maneuver!.type, 'turn');
      expect(state.maneuver!.modifier, 'right');
      expect(state.maneuver!.text, 'Adelaide St W');
      expect(state.maneuver!.secondaryText, 'Toward Spadina');
      expect(state.distanceToManeuver, 350.4);
      expect(state.then!.type, 'arrive');
      expect(state.lanes, hasLength(2));
      expect(state.lanes[0].valid, isFalse);
      expect(state.lanes[1].indications, ['straight', 'right']);
      expect(state.lanes[1].activeIndication, 'right');
      expect(state.speedLimit, 40);
      expect(state.speedLimitUnit, 'km/h');
      expect(state.distanceRemaining, 2400);
      expect(state.durationRemaining, 480.5);
      expect(state.eta, DateTime.fromMillisecondsSinceEpoch(1760000000000));
      expect(state.offRoute, isFalse);
      expect(state.rerouting, isTrue);
      expect(state.fasterRoute!.savingSeconds, 240);
      expect(state.latitude, 43.65);
      expect(state.bearing, 71.5);
    });

    test('parses the same payload sent as a JSON string (iOS)', () {
      final state = NavState.tryParse(jsonEncode(full))!;
      expect(state.maneuver!.text, 'Adelaide St W');
      expect(state.lanes, hasLength(2));
      expect(state.fasterRoute!.savingSeconds, 240);
    });

    test('a sparse payload leaves everything optional empty', () {
      final state = NavState.tryParse(const <String, dynamic>{})!;
      expect(state.maneuver, isNull);
      expect(state.then, isNull);
      expect(state.lanes, isEmpty);
      expect(state.speedLimit, isNull);
      expect(state.eta, isNull);
      expect(state.fasterRoute, isNull);
      expect(state.offRoute, isFalse);
      expect(state.rerouting, isFalse);
      expect(state.arrived, isFalse);
    });

    test('wrong scalar types are ignored, not thrown', () {
      final state = NavState.tryParse(const <String, dynamic>{
        'maneuver': 'turn right',
        'speedLimit': '40',
        'fasterRoute': {'savingSeconds': 'lots'},
        'etaEpochMs': 'soon',
      })!;
      expect(state.maneuver, isNull);
      expect(state.speedLimit, isNull);
      expect(state.fasterRoute, isNull);
      expect(state.eta, isNull);
    });

    test('garbage is null', () {
      expect(NavState.tryParse(null), isNull);
      expect(NavState.tryParse(''), isNull);
      expect(NavState.tryParse('not json'), isNull);
      expect(NavState.tryParse(42), isNull);
    });
  });

  group('RouteEvent', () {
    test('nav_state event carries a NavState', () {
      final event = RouteEvent.fromJson(
        const {'eventType': 'nav_state', 'data': full},
      );
      expect(event.eventType, MapBoxEvent.nav_state);
      expect(event.data, isA<NavState>());
    });

    test('nav_state with a string payload carries a NavState', () {
      final event = RouteEvent.fromJson(
        {'eventType': 'nav_state', 'data': jsonEncode(full)},
      );
      expect((event.data as NavState).speedLimit, 40);
    });

    test('camera_state maps to the enum', () {
      for (final entry in const {
        'following': NavCameraState.following,
        'overview': NavCameraState.overview,
        'free': NavCameraState.free,
      }.entries) {
        final event = RouteEvent.fromJson(
          {'eventType': 'camera_state', 'data': entry.key},
        );
        expect(event.eventType, MapBoxEvent.camera_state);
        expect(event.data, entry.value);
      }
    });

    test('an unknown camera state is null', () {
      final event = RouteEvent.fromJson(
        const {'eventType': 'camera_state', 'data': 'spinning'},
      );
      expect(event.data, isNull);
    });

    test('older events still parse', () {
      final event = RouteEvent.fromJson(
        const {'eventType': 'route_built', 'data': '[]'},
      );
      expect(event.eventType, MapBoxEvent.route_built);
    });
  });
}
