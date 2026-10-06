import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Genera `.next/standalone` (servidor mínimo con solo las dependencias trazadas) para la imagen de contenedor.
  output: "standalone",
};

export default nextConfig;
