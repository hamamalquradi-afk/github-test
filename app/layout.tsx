import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "متابعة الاشتراكات والتحصيل",
  description: "إدارة العملاء والاشتراكات والدفعات والتنبيهات",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ar" dir="rtl">
      <body>
        <header className="app-header">
          <div className="header-inner">
            <Link className="brand" href="/">متابعة الاشتراكات</Link>
            <nav className="main-nav" aria-label="التنقل الرئيسي">
              <Link href="/">الرئيسية</Link>
              <Link href="/customers">العملاء</Link>\n              <Link href="/settings">الإعدادات</Link>
            </nav>
          </div>
        </header>
        <main>{children}</main>
      </body>
    </html>
  );
}
