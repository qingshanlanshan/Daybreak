// daybreak-helper: small native jobs for the Mac app (it runs inside Daybreak.app, so macOS treats it as Daybreak).
//   daybreak-helper notify <id> <title> <body>   show a notification
//   daybreak-helper location                     print "lat,lon" (or "error: …")
import CoreLocation
import Foundation
import UserNotifications

let args = CommandLine.arguments
func done(_ s: String, _ code: Int32 = 0) -> Never { print(s); fflush(stdout); exit(code) }

if args.count >= 5 && args[1] == "notify" {
    let center = UNUserNotificationCenter.current()
    center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
        if !granted { done("error: notifications are off for Daybreak", 2) }
        let content = UNMutableNotificationContent()
        content.title = args[3]
        content.body = args[4]
        content.sound = .default
        center.add(UNNotificationRequest(identifier: args[2], content: content, trigger: nil)) { err in
            if let err { done("error: \(err.localizedDescription)", 1) }
            done("ok")
        }
    }
    RunLoop.main.run(until: Date().addingTimeInterval(20))
    done("error: timed out", 1)
}

final class Locator: NSObject, CLLocationManagerDelegate {
    let manager = CLLocationManager()
    func start() {
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyKilometer
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .denied, .restricted: done("error: location is off for Daybreak (System Settings → Privacy & Security → Location Services)", 2)
        default: manager.requestLocation()
        }
    }
    func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        switch m.authorizationStatus {
        case .authorizedAlways, .authorized: m.requestLocation()
        case .denied, .restricted: done("error: location is off for Daybreak (System Settings → Privacy & Security → Location Services)", 2)
        default: break
        }
    }
    func locationManager(_ m: CLLocationManager, didUpdateLocations l: [CLLocation]) {
        if let c = l.last?.coordinate { done(String(format: "%.4f,%.4f", c.latitude, c.longitude)) }
    }
    func locationManager(_ m: CLLocationManager, didFailWithError e: Error) { done("error: \(e.localizedDescription)", 1) }
}

if args.count >= 2 && args[1] == "location" {
    let l = Locator()
    l.start()
    RunLoop.main.run(until: Date().addingTimeInterval(30))
    done("error: timed out", 1)
}
done("usage: daybreak-helper notify <id> <title> <body> | location", 64)
