# F01 feature journeys

### TC-F01-002 First real staging deployment serves salon subdomains with public TLS

- **Type:** security
- **Priority:** high
- **Covers:** BRD-03
- **Preconditions:** The owner has chosen hosting and the platform domain, configured DNS
  challenge credentials and the Caddy provider module, provided host access, and requested staging
  deployment. Synthetic data only.
- **Steps:** Deploy a chosen published platform version with the manual staging workflow. Reach
  the bare platform and alpha, beta and gamma through public TLS; add a synthetic salon and reach
  it on its first request. Check remote telemetry and repeat deployment and rollback under the
  approved staging load.
- **Expected:** Public certificates verify without a local trust override; each hostname serves
  only its own salon, deployment and rollback preserve availability, and remote telemetry is
  collected on the chosen host.
- **Automation:** Deferred by decision 018. The local production-like stack is tested separately;
  this case waits for hosting, domain, DNS module and credentials, host access and an explicit
  staging deployment request.
