import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import Link from "next/link";
import { connection } from "next/server";
import { runtimeConfigScript } from "@/lib/config";
import "./globals.css";
import styles from "./layout.module.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "QueueLab",
  description: "Panel de control de la plataforma de procesamiento de trabajos QueueLab",
};

export default async function RootLayout({ children }: LayoutProps<"/">) {
  // La URL de la API se lee del entorno en cada petición (no al compilar): ver lib/config.ts.
  await connection();
  return (
    <html lang="es" className={`${geistSans.variable} ${geistMono.variable}`}>
      <head>
        <script dangerouslySetInnerHTML={{ __html: runtimeConfigScript() }} />
      </head>
      <body>
        <header className={styles.header}>
          <Link href="/jobs" className={styles.brand}>
            QueueLab
          </Link>
          <nav aria-label="Principal">
            <Link href="/jobs">Trabajos</Link>
            <Link href="/jobs/new">Nuevo CSV</Link>
          </nav>
        </header>
        <main className={styles.main}>{children}</main>
      </body>
    </html>
  );
}
