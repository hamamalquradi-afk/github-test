import type { Metadata } from "next";
import Link from "next/link";
import { APP_DESCRIPTION, APP_NAME, APP_SUBTITLE } from "../lib/app-info";
import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: APP_NAME,
    template: `%s | ${APP_NAME}`,
  },
  description: APP_DESCRIPTION,
  icons: {
    icon: "/branding/hamnova-icon.png",
    apple: "/branding/hamnova-icon.png",
  },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ar" dir="rtl">
      <body>
        <header className="app-header">
          <div className="header-inner">
            <Link className="brand-lockup" href="/" aria-label={`${APP_NAME} — ${APP_SUBTITLE}`}>
              <img className="brand-icon" src="/branding/hamnova-icon.png" alt="" width="44" height="44" />
              <span className="brand-copy">
                <strong className="brand-wordmark" dir="ltr">{APP_NAME}</strong>
                <small className="brand-subtitle">{APP_SUBTITLE}</small>
              </span>
            </Link>
            <nav className="main-nav" aria-label="التنقل الرئيسي">
              <Link href="/">الرئيسية</Link>
              <Link href="/customers">العملاء</Link>
              <Link href="/settings">الإعدادات</Link>
              <Link href="/contact">التواصل</Link>
            </nav>
          </div>
        </header>
        <main>{children}</main>
      </body>
    </html>
  );
}
