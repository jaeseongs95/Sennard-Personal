-- Test only: 6.0.0 ships no contract script. This one lets the tests run the contract instance of Flyway after an expand, on a
-- database whose sennard is already filled (the baselineOnMigrate case).
CREATE TABLE sennard.contract_probe (id integer NOT NULL);
