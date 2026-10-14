// Checks the rules of the bird's-eye view at a turn (ios/Classes/TurnView.swift).
// Run with tool/check_turn_view.sh. Android's TurnView.kt is the same
// rules line for line; a change to one is a change to both.
import Foundation

var failures = 0
func expect(_ what: String, _ ok: Bool) {
    if !ok { failures += 1; print("FAIL  \(what)") }
}

@main
struct Check {
    static func main() {
        // What counts as a turn.
        expect("turn right", TurnView.needsChoice(type: "turn", modifier: "right"))
        expect("slight turn", TurnView.needsChoice(type: "turn", modifier: "slight left"))
        expect("turn straight is not", !TurnView.needsChoice(type: "turn", modifier: "straight"))
        expect("fork", TurnView.needsChoice(type: "fork", modifier: "slight left"))
        expect("off ramp", TurnView.needsChoice(type: "off ramp", modifier: "slight right"))
        expect("on ramp", TurnView.needsChoice(type: "on ramp", modifier: "slight right"))
        expect("roundabout", TurnView.needsChoice(type: "roundabout", modifier: nil))
        expect("end of road", TurnView.needsChoice(type: "end of road", modifier: "left"))
        expect("continue straight is not", !TurnView.needsChoice(type: "continue", modifier: "straight"))
        expect("continue left", TurnView.needsChoice(type: "continue", modifier: "left"))
        expect("new name is not", !TurnView.needsChoice(type: "new name", modifier: "slight right"))
        expect("merge is not", !TurnView.needsChoice(type: "merge", modifier: "slight left"))
        expect("depart is not", !TurnView.needsChoice(type: "depart", modifier: "right"))
        expect("arrive is not", !TurnView.needsChoice(type: "arrive", modifier: "left"))
        expect("nothing is not", !TurnView.needsChoice(type: nil, modifier: nil))

        // How far out, by speed.
        expect("crawling: 120 m", TurnView.triggerMetres(speed: 2) == 120)
        expect("60 km/h: 250 m", abs(TurnView.triggerMetres(speed: 60 / 3.6) - 250) < 0.01)
        expect("130 km/h: 541 m", abs(TurnView.triggerMetres(speed: 130 / 3.6) - 541.67) < 0.01)
        expect("capped at 600 m", TurnView.triggerMetres(speed: 60) == 600)
        expect("no speed: 120 m", TurnView.triggerMetres(speed: -1) == 120 && TurnView.triggerMetres(speed: .nan) == 120)

        func next(_ state: TurnView.State, step: Int = 1, choice: Bool = true, to: Double?, into: Double = 500,
                  speed: Double = 60 / 3.6) -> TurnView.State {
            TurnView.next(state, stepKey: step, needsChoice: choice, distanceToManeuver: to,
                          distanceIntoStep: into, speed: speed)
        }
        let tilted = TurnView.State()
        expect("far from the turn: tilted", !next(tilted, to: 400).topDown)
        expect("inside 15 s: up", next(tilted, to: 249).topDown)
        expect("a straight-on step: tilted", !next(tilted, choice: false, to: 50).topDown)
        expect("no distance: tilted", !next(tilted, to: nil).topDown)

        // Braking for the turn does not take the view away again.
        var up = next(tilted, to: 249)
        up = next(up, to: 200, speed: 3)
        expect("braking keeps it up", up.topDown)
        // Nor does a fix that jumps back.
        up = next(up, to: 215, speed: 3)
        expect("jitter keeps it up", up.topDown)
        // A new route that puts the turn far off does.
        expect("turn moved away: down", !next(up, to: 249 + 61, speed: 3).topDown)

        // Speed hovering at the threshold: one way in, no flapping.
        var hover = tilted
        var changes = 0
        for (distance, speed) in [(260.0, 17.0), (255.0, 17.2), (250.0, 16.5), (246.0, 16.2), (242.0, 17.0), (238.0, 15.5)] {
            let was = hover.topDown
            hover = next(hover, to: distance, speed: speed)
            if hover.topDown != was { changes += 1 }
        }
        expect("hovering at the threshold changes once", changes == 1 && hover.topDown)

        // Through the turn: held, then back.
        let through = next(up, step: 2, choice: true, to: 900, into: 5)
        expect("just past the turn: held", through.topDown)
        expect("still held at 39 m", next(through, step: 2, to: 866, into: 39).topDown)
        expect("back to tilted at 40 m", !next(through, step: 2, to: 865, into: 40).topDown)
        // The next turn follows at once: stays up, and for the new turn.
        let chained = next(up, step: 2, to: 90, into: 5)
        expect("turn straight after a turn: stays up", chained.topDown && chained.stepKey == 2)
        expect("and through the hold", next(chained, step: 2, to: 45, into: 50).topDown)
        // Off the route.
        expect("off route: down", !next(up, choice: false, to: 100, into: .infinity).topDown)

        // The zoom.
        let lat = 43.65
        let straight = [(ahead: 250.0, side: 0.0), (ahead: 310.0, side: 0.0)]
        let far = TurnView.fitZoom(points: straight, aheadPoints: 430, halfWidthPoints: 180, latitude: lat)
        expect("250 m out: about zoom 16", far > 15.6 && far < 16.3)
        let near = TurnView.fitZoom(points: [(ahead: 30.0, side: 0.0), (ahead: 30.0, side: 60.0)],
                                    aheadPoints: 430, halfWidthPoints: 180, latitude: lat)
        expect("at the turn: closer, the side street in view", near > far && near < TurnView.maxZoom)
        expect("crawling up to it: no closer than the limit",
               TurnView.fitZoom(points: [(ahead: 4.0, side: 0.0)], aheadPoints: 430, halfWidthPoints: 180, latitude: lat) == TurnView.maxZoom)
        expect("a long way off: no further out than the limit",
               TurnView.fitZoom(points: [(ahead: 5000.0, side: 0.0)], aheadPoints: 430, halfWidthPoints: 180, latitude: lat) == TurnView.minZoom)
        expect("nothing to fit", TurnView.fitZoom(points: [], aheadPoints: 430, halfWidthPoints: 180, latitude: lat) == TurnView.maxZoom)

        // The ease.
        expect("step up", TurnView.step(from: 0, to: 45, most: 25) == 25)
        expect("step down", TurnView.step(from: 45, to: 0, most: 25) == 20)
        expect("step lands", TurnView.step(from: 20, to: 0, most: 25) == 0)

        print(failures == 0 ? "turn view: all checks pass" : "turn view: \(failures) failed")
        exit(failures == 0 ? 0 : 1)
    }
}
