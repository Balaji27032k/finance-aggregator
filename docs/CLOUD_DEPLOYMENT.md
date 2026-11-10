# Cloud Deployment (Oracle Cloud Always Free)

Backend runs on one Always Free VM: Caddy (HTTPS, automatic certificate) in front of the Spring Boot jar on 127.0.0.1:8080, talking to Aiven MySQL over TLS. MySQL and Kafka are never exposed by this VM.

## Facts checked against Oracle docs (2026-06 revision)
- Always Free compute: up to two `VM.Standard.E2.1.Micro` (AMD, 1/8 OCPU, 1 GB), or Ampere A1 with 2 OCPU / 12 GB in total.
- Resources must be created in the tenancy's **home region**, chosen at sign-up.
- "Out of host capacity" can occur; try another availability domain or retry later.
- **Idle reclamation:** Oracle may reclaim an Always Free instance if, over 7 days, CPU p95 < 20% and network < 20% (and memory < 20% on A1). This backend is mostly idle, so reclamation is a real risk. The phone queues events, so a lost VM delays sync but loses nothing; re-create the VM and re-run the steps.
- Outbound data 10 TB/month, 200 GB total block/boot storage.

## Steps
1. Create the Oracle account (card used for identity verification). Pick a home region near you.
2. Compute > Instances > Create instance. Image: Ubuntu 24.04 (Always Free eligible). Shape: VM.Standard.A1.Flex 1 OCPU / 6 GB, or VM.Standard.E2.1.Micro if A1 is out of capacity. Assign a public IPv4. Add your SSH public key (or download the generated key).
3. Networking > your VCN > subnet > Security List > Add ingress rules: TCP 80 and TCP 443 from 0.0.0.0/0. (22 is already open; restrict it to your IP if you can.)
4. Get a hostname: https://www.duckdns.org, sign in, create a subdomain, set its IP to the VM's public IP.
5. Aiven console > MySQL service > Allowed IP addresses: add the VM's public IP.
6. On Windows run `deployment\scripts\prepare-deploy.ps1`. It builds the jar and creates `%TEMP%\finance-deploy` (contains secrets: do not commit or share).
7. `scp -i <key> -r %TEMP%\finance-deploy ubuntu@<VM-IP>:~/finance-deploy`
8. `ssh -i <key> ubuntu@<VM-IP>`, then `cd ~/finance-deploy && sudo bash setup-server.sh <your-subdomain>.duckdns.org`
9. Install the app files:
   ```
   sudo install -m 640 -o root -g finance env /etc/finance-aggregator/env
   sudo install -m 640 -o root -g finance aiven-ca.p12 /etc/finance-aggregator/secrets/aiven-ca.p12
   sudo install -m 644 app.jar /opt/finance-aggregator/app.jar
   sudo systemctl restart finance-backend
   ```
   On the 1 GB Micro shape first run: `sudo sed -i 's/-Xmx512m/-Xmx320m/' /etc/systemd/system/finance-backend.service && sudo systemctl daemon-reload`, and add swap (`sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile`).
10. Verify: `curl https://<subdomain>.duckdns.org/actuator/health` returns `{"status":"UP"}`. Logs: `journalctl -u finance-backend -f`.
11. Remove secrets from the home folder: `shred -u ~/finance-deploy/env; rm -rf ~/finance-deploy`.
12. Phone > Health > Cloud sync: enter `https://<subdomain>.duckdns.org` and the pairing code from the local `.env` (`DEVICE_PAIRING_CODE`), tap Pair, then Sync now.

## Updating
Rebuild with `prepare-deploy.ps1`, `scp` only `app.jar`, then `sudo install -m 644 app.jar /opt/finance-aggregator/app.jar && sudo systemctl restart finance-backend`.

## Backup and restore
Aiven keeps automated backups of the MySQL service. For a manual copy, from a machine allowed by Aiven: `mysqldump --ssl-mode=VERIFY_CA --ssl-ca=aiven-ca.pem -h <host> -P <port> -u <user> -p defaultdb > backup.sql`; restore with `mysql ... < backup.sql`. Keep `PAYLOAD_ENCRYPTION_KEY`: without it the encrypted payloads in a backup cannot be read.

## Security notes
- Only 22, 80 and 443 are reachable. The app listens on 127.0.0.1 only; Caddy forwards `/api/v1/*` and `/actuator/health` and returns 404 for everything else.
- Secrets live in `/etc/finance-aggregator` (root:finance, mode 640). The service runs as an unprivileged user with systemd sandboxing.
- Rotate the pairing code and database password by editing the env file and restarting the service.
