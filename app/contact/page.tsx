import type { Metadata } from "next";
import { APP_DESCRIPTION, APP_NAME, APP_SUBTITLE, APP_VERSION, CONTACT, OWNER_NAME } from "../../lib/app-info";

export const metadata: Metadata = {
  title: "عن HAMNOVA والتواصل",
  description: APP_DESCRIPTION,
};

export default function ContactPage() {
  const hasContact = Boolean(CONTACT.phone || CONTACT.whatsapp || CONTACT.email);

  return <>
    <section className="page-heading contact-heading">
      <div>
        <span className="eyebrow">عن المنتج</span>
        <h1>عن HAMNOVA والتواصل</h1>
        <p>معلومات المنتج وصاحب المشروع وخصوصية التخزين المحلي.</p>
      </div>
    </section>

    <section className="contact-card">
      <img
        className="contact-logo"
        src="/branding/hamnova-logo.png"
        alt="شعار HAMNOVA — إدارة الاشتراكات والتحصيل"
        width="720"
        height="540"
      />
      <div className="contact-copy">
        <p className="contact-name" dir="ltr">{APP_NAME}</p>
        <p className="contact-subtitle">{APP_SUBTITLE}</p>
        <p>{APP_DESCRIPTION}، تطبيق محلي يساعد على متابعة العملاء والاشتراكات والدفعات والتنبيهات والنسخ الاحتياطي.</p>
        <dl className="contact-facts">
          <div><dt>صاحب المشروع</dt><dd>{OWNER_NAME}</dd></div>
          <div><dt>الإصدار</dt><dd dir="ltr">{APP_VERSION}</dd></div>
        </dl>
        <div className="privacy-panel">
          <strong>الخصوصية والتخزين</strong>
          <p>تُخزّن بيانات العمل محليًا على جهاز المستخدم، ولا يتم رفعها تلقائيًا إلى خادم خارجي لبيانات الأعمال.</p>
        </div>

        {hasContact && <div className="contact-actions" aria-label="وسائل التواصل">
          {CONTACT.phone && <a href={`tel:${CONTACT.phone}`}>اتصال</a>}
          {CONTACT.whatsapp && <a href={`https://wa.me/${CONTACT.whatsapp}`}>واتساب</a>}
          {CONTACT.email && <a href={`mailto:${CONTACT.email}`}>بريد إلكتروني</a>}
        </div>}
      </div>
    </section>
  </>;
}
