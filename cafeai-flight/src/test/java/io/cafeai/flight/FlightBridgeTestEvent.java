package io.cafeai.flight;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** A trivial custom JFR event, used to prove FlightBridge's plumbing deterministically. */
@Name("cafeai.flight.test.TestEvent")
@Label("Flight Bridge Test Event")
final class FlightBridgeTestEvent extends Event {
}
