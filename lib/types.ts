export interface Payment {
  id: string;
  amount: number | string;
  payment_date: string;
  notes: string | null;
}

export interface Subscription {
  id: string;
  customer_id: string;
  subscription_name: string;
  amount: number | string;
  start_date: string;
  end_date: string;
  status: string;
  created_at: string;
  payments: Payment[];
}

export interface Customer {
  id: string;
  name: string;
  phone: string;
  notes: string | null;
  created_at: string;
  subscriptions: Subscription[];
}

export interface ReminderView {
  id: string;
  scheduled_date: string;
  sent_at: string | null;
  status: "PENDING" | "DUE" | "SENT";
  subscriptions: {
    status: string;
    subscription_name: string;
    end_date: string;
    customers: { name: string; phone: string };
  };
}
