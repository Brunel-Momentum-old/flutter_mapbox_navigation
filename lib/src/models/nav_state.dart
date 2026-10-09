// ignore_for_file: public_member_api_docs

import 'dart:convert';

/// One instruction the driver has to follow: what to do and onto which
/// road. [type] and [modifier] are the Directions API vocabulary
/// (`turn` / `merge` / `arrive` / `roundabout` …, and `left` /
/// `slight right` / `uturn` / `straight` …).
class NavManeuver {
  const NavManeuver({this.type, this.modifier, this.text, this.secondaryText});

  factory NavManeuver.fromJson(Map<String, dynamic> json) => NavManeuver(
        type: _string(json['type']),
        modifier: _string(json['modifier']),
        text: _string(json['text']),
        secondaryText: _string(json['secondaryText']),
      );

  final String? type;
  final String? modifier;

  /// Primary banner text, usually the road being turned onto.
  final String? text;
  final String? secondaryText;

  @override
  bool operator ==(Object other) =>
      other is NavManeuver &&
      other.type == type &&
      other.modifier == modifier &&
      other.text == text &&
      other.secondaryText == secondaryText;

  @override
  int get hashCode => Object.hash(type, modifier, text, secondaryText);
}

/// One lane at the upcoming maneuver.
class NavLane {
  const NavLane({
    this.indications = const [],
    this.valid = false,
    this.activeIndication,
  });

  factory NavLane.fromJson(Map<String, dynamic> json) => NavLane(
        indications: [
          if (json['indications'] case final List<dynamic> indications)
            for (final i in indications)
              if (i != null) i.toString(),
        ],
        valid: json['valid'] == true,
        activeIndication: _string(json['active']),
      );

  /// Every direction painted on the lane (`left`, `straight`, …).
  final List<String> indications;

  /// Whether the lane can be used for the upcoming maneuver.
  final bool valid;

  /// Which of [indications] applies to the route, when the SDK says.
  final String? activeIndication;

  @override
  bool operator ==(Object other) =>
      other is NavLane &&
      other.valid == valid &&
      other.activeIndication == activeIndication &&
      other.indications.join(',') == indications.join(',');

  @override
  int get hashCode =>
      Object.hash(valid, activeIndication, indications.join(','));
}

/// A faster route the SDK has found. It is only ever an offer: nothing
/// switches until `acceptFasterRoute` is called.
class NavFasterRoute {
  const NavFasterRoute({required this.savingSeconds});

  final double savingSeconds;

  @override
  bool operator ==(Object other) =>
      other is NavFasterRoute && other.savingSeconds == savingSeconds;

  @override
  int get hashCode => savingSeconds.hashCode;
}

/// Everything a custom turn-by-turn UI needs, sent once per progress
/// tick while guidance is running.
class NavState {
  const NavState({
    this.maneuver,
    this.distanceToManeuver,
    this.then,
    this.lanes = const [],
    this.speedLimit,
    this.speedLimitUnit,
    this.distanceRemaining,
    this.durationRemaining,
    this.eta,
    this.offRoute = false,
    this.rerouting = false,
    this.fasterRoute,
    this.arrived = false,
    this.latitude,
    this.longitude,
    this.bearing,
    this.speed,
    this.roadName,
  });

  /// Accepts the event payload as a map (Android) or a JSON string (iOS).
  /// Returns null for anything else.
  static NavState? tryParse(Object? data) {
    Object? decoded = data;
    if (decoded is String) {
      if (decoded.isEmpty) return null;
      try {
        decoded = jsonDecode(decoded);
      } on FormatException {
        return null;
      }
    }
    if (decoded is! Map) return null;
    return NavState.fromJson(decoded.cast<String, dynamic>());
  }

  factory NavState.fromJson(Map<String, dynamic> json) {
    final maneuver = json['maneuver'];
    final then = json['then'];
    final faster = json['fasterRoute'];
    final etaMs = _num(json['etaEpochMs']);
    return NavState(
      maneuver: maneuver is Map
          ? NavManeuver.fromJson(maneuver.cast<String, dynamic>())
          : null,
      distanceToManeuver: _num(json['distanceToManeuver'])?.toDouble(),
      then: then is Map
          ? NavManeuver.fromJson(then.cast<String, dynamic>())
          : null,
      lanes: [
        if (json['lanes'] case final List<dynamic> lanes)
          for (final lane in lanes)
            if (lane is Map) NavLane.fromJson(lane.cast<String, dynamic>()),
      ],
      speedLimit: _num(json['speedLimit'])?.round(),
      speedLimitUnit: _string(json['speedLimitUnit']),
      distanceRemaining: _num(json['distanceRemaining'])?.toDouble(),
      durationRemaining: _num(json['durationRemaining'])?.toDouble(),
      eta: etaMs == null
          ? null
          : DateTime.fromMillisecondsSinceEpoch(etaMs.round()),
      offRoute: json['offRoute'] == true,
      rerouting: json['rerouting'] == true,
      fasterRoute: faster is Map && _num(faster['savingSeconds']) != null
          ? NavFasterRoute(
              savingSeconds: _num(faster['savingSeconds'])!.toDouble(),
            )
          : null,
      arrived: json['arrived'] == true,
      latitude: _num(json['latitude'])?.toDouble(),
      longitude: _num(json['longitude'])?.toDouble(),
      bearing: _num(json['bearing'])?.toDouble(),
      speed: _num(json['speed'])?.toDouble(),
      roadName: _string(json['roadName']),
    );
  }

  /// The upcoming maneuver. Null before the first instruction arrives.
  final NavManeuver? maneuver;

  /// Metres to [maneuver].
  final double? distanceToManeuver;

  /// The maneuver straight after [maneuver], when the SDK announces one.
  final NavManeuver? then;

  /// Lanes at [maneuver], left to right. Empty when there is no lane
  /// guidance.
  final List<NavLane> lanes;

  /// Posted limit in [speedLimitUnit] (`km/h` or `mph`). Null when
  /// unknown.
  final int? speedLimit;
  final String? speedLimitUnit;

  /// Metres and seconds left on the whole route.
  final double? distanceRemaining;
  final double? durationRemaining;
  final DateTime? eta;

  /// The driver has left the route and no new one has replaced it yet.
  final bool offRoute;

  /// A new route is being fetched.
  final bool rerouting;

  /// Set while a faster route is on offer.
  final NavFasterRoute? fasterRoute;

  final bool arrived;

  /// The matched location guidance is running on.
  final double? latitude;
  final double? longitude;
  final double? bearing;

  /// Metres per second.
  final double? speed;
  final String? roadName;
}

/// What the map camera is doing.
enum NavCameraState {
  /// Tracking the driver.
  following,

  /// Showing the whole remaining route.
  overview,

  /// Left where the driver panned it.
  free;

  static NavCameraState? tryParse(Object? data) {
    var value = data;
    if (value is String && value.startsWith('"')) {
      try {
        value = jsonDecode(value);
      } on FormatException {
        return null;
      }
    }
    return switch (value) {
      'following' => NavCameraState.following,
      'overview' => NavCameraState.overview,
      'free' => NavCameraState.free,
      _ => null,
    };
  }
}

String? _string(Object? value) {
  final s = value?.toString().trim();
  return s == null || s.isEmpty ? null : s;
}

num? _num(Object? value) => value is num ? value : null;
