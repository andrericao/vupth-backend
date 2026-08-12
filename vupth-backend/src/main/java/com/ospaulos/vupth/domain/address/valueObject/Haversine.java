package com.ospaulos.vupth.domain.address.valueObject;

/**
 * Distancia sobre a superficie da Terra entre duas coordenadas (backlog #2, fatia B).
 *
 * <p><b>Por que Haversine e nao PostGIS:</b> a restricao de custo zero vale tambem para infra — o
 * Postgres free do Render/Railway pode nem permitir a extensao, e o volume de estabelecimentos nao
 * justifica indice espacial. A formula e trigonometria pura, roda em qualquer lugar e nao depende de
 * nada externo.</p>
 *
 * <p><b>Precisao:</b> assume a Terra esferica (raio medio), o que erra ~0,5% no pior caso contra o
 * elipsoide real. Para "quao longe fica esta barbearia" isso e irrelevante — a diferenca entre 2,3km
 * e 2,31km nao muda a decisao de ninguem.</p>
 */
public final class Haversine {

    /** Raio medio da Terra em km (IUGG). */
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private Haversine() {
    }

    /**
     * Distancia em km entre dois pontos. Sempre >= 0; pontos iguais dao exatamente 0.
     */
    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);

        double sinLat = Math.sin(dLat / 2);
        double sinLng = Math.sin(dLng / 2);

        double a = sinLat * sinLat
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLng * sinLng;

        // Math.min protege contra a > 1 por erro de arredondamento em pontos coincidentes,
        // que faria o asin devolver NaN.
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * Arredonda para 2 casas — a precisao que o app mostra ("2,3 km"). Devolver o double cru faria
     * o JSON carregar 15 digitos de ruido que nenhum front usa.
     */
    public static double roundKm(double km) {
        return Math.round(km * 100.0) / 100.0;
    }
}
