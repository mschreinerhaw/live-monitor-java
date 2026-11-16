-- Portable, additive schema; the application also creates these tables on upgrade.
CREATE TABLE IF NOT EXISTS t_ld_trading_calendar (
 ZRR INT NOT NULL, JYR INT NOT NULL, ND INT, JD INT, YF_XH INT, XQ INT, NY INT, NZ INT, XQ_XH INT,
 PRIMARY KEY (ZRR, JYR)
);
CREATE TABLE IF NOT EXISTS t_ld_working_calendar (
 ZRR INT NOT NULL, GZR INT NOT NULL, ND INT, JD INT, YF_XH INT, XQ INT, NY INT, NZ INT, XQ_XH INT,
 PRIMARY KEY (ZRR, GZR)
);
