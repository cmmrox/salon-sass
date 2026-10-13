-- Additive defaults preserve the previous application's customer inserts during deployment.
ALTER TABLE identity_customer ADD COLUMN email text;
ALTER TABLE identity_customer ADD COLUMN birthday date;
ALTER TABLE identity_customer ADD COLUMN preferred_language text NOT NULL DEFAULT 'en';
ALTER TABLE identity_customer ADD COLUMN notify_sms boolean NOT NULL DEFAULT true;
ALTER TABLE identity_customer ADD COLUMN notify_whatsapp boolean NOT NULL DEFAULT true;
ALTER TABLE identity_customer ADD COLUMN notify_email boolean NOT NULL DEFAULT true;
ALTER TABLE identity_customer ADD COLUMN marketing_consent boolean NOT NULL DEFAULT false;
ALTER TABLE identity_customer ADD CONSTRAINT customer_language_known CHECK (preferred_language IN ('en','si','ta'));
