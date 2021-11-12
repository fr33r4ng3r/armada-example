package armada.example.api;

import java.util.List;

public record TheatreData(int gridWidth, int gridHeight, int numberOfShips, List<ShipData> ships) {
    public static record ShipData(String descriptor, int width, int length) {

    }
}
