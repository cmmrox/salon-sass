# Glossary

The words of the business, as the code, the API and the screens use them. One word for one thing,
everywhere.

| Word | Meaning |
| --- | --- |
| Tenant, salon | One subscribing business. All of its data carries its `tenant_id` |
| Operator | The company running the platform. Operator users are not tenant users |
| Owner, Manager, Receptionist, Professional, Accountant | The staff roles of a salon, each with a defined set of permissions |
| Professional | A staff member who performs services and has a calendar |
| Customer | A person who books with one salon. The same phone number at two salons is two unrelated customers |
| Service | Something bookable, with a price, a duration and a buffer. Its time is stored as segments: active, processing (the professional is free) and buffer |
| Product | Something sold at checkout with a price and tax; stock is not tracked in R1 |
| Price level | A professional's grade (for example Junior, Senior, Director) that can set a service's price and duration (R2) |
| Branch | One location of a salon. Every salon has a default branch; more than one is R2 |
| Hold | A slot reserved for a short time (ten minutes by default) while the customer finishes booking |
| Booking | An appointment. States: held, pending payment, confirmed, arrived, in service, completed, cancelled, no-show. Completed only through checkout; no-show only after the start time |
| Front desk | The booking source for a booking made in person at the salon for a later time; a walk-in starts now or joins the queue |
| Tender | How a payment was made: cash, card terminal, bank transfer (with its reference), LANKAQR or the gateway |
| Payment link | A link sent by WhatsApp or SMS that lets a customer pay a staff-created booking's deposit at the gateway |
| Day-end close | Counting the cash drawer against the expected cash and recording any difference |
| Add-on module | A module the operator activates for one salon beyond its plan, such as Bridal |
| Deposit | A part payment taken at booking, set by the salon's policy |
| Policy | A salon's own rules: deposit, free-cancellation window, what is kept on a late cancellation |
| Walk-in | A customer who arrives without a booking |
| Booking site | A salon's public pages at its own web address |
| Workspace | The staff side of the web app, under the same web address |
| Console | The operator's side of the web app, on a reserved hostname |
| Container app | The shared customer app in the operator's store accounts; a link, QR code or short code opens one salon inside it |
| Dedicated app | A salon's own store app, built from the same code and published in the salon's own developer accounts |

Business rules read from the prototype (deposit calculation, cancellation window, phone validation)
are in the BRD, section 4, rules BR-01 to BR-22. Where a requirement changes a prototype rule, the
requirement wins.
