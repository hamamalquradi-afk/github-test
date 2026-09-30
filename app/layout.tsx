import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "متابعة الاشتراكات والتحصيل",
  description: "إدارة العملاء والاشتراكات والدفعات",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ar" dir="rtl">
      <body>
        <header><a href="/">متابعة الاشتراكات</a></header>
        <main>{children}</main>
      </body>
    </html>
  );
}
