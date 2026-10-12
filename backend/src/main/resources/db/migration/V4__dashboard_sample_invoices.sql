-- A dedicated, clearly labelled sample dataset. Existing source invoices are untouched.
CREATE TABLE toscana_insights.dashboard_sample_invoices (
    id varchar(20) NOT NULL PRIMARY KEY,
    vendor nvarchar(120) NOT NULL,
    business_unit nvarchar(60) NOT NULL,
    invoice_date date NOT NULL,
    amount decimal(14,2) NOT NULL CHECK (amount >= 0),
    currency char(3) NOT NULL DEFAULT 'USD',
    status varchar(20) NOT NULL CHECK (status IN ('Paid','In review','Scheduled','Exception')),
    cycle_days decimal(4,1) NOT NULL,
    automated bit NOT NULL,
    sample_as_of date NOT NULL
);
GO

;WITH days AS (
    SELECT 0 AS age UNION ALL SELECT age + 1 FROM days WHERE age < 179
), items AS (
    SELECT 0 AS item UNION ALL SELECT item + 1 FROM items WHERE item < 34
), samples AS (
    SELECT age, item, age * 37 + item * 13 AS seed
    FROM days CROSS JOIN items WHERE item < 22 + (180 - age) % 14
)
INSERT INTO toscana_insights.dashboard_sample_invoices
    (id,vendor,business_unit,invoice_date,amount,currency,status,cycle_days,automated,sample_as_of)
SELECT CONCAT('INV-',10800 - age * 40 - item),
    CHOOSE(seed % 8 + 1,'Microsoft','Acme Technologies','Deloitte','Amazon Web Services','Adobe','Notion Labs','Salesforce','WeWork'),
    CHOOSE((age + item) % 5 + 1,'Technology','Operations','Finance','Marketing','People'),
    DATEADD(day,-age,CONVERT(date,'20261009',112)),
    380 + seed % 9200 + (item % 10) * 0.25, 'USD',
    CASE WHEN seed % 19 = 0 THEN 'Exception' WHEN age < 12 AND seed % 4 = 0 THEN 'In review'
         WHEN age < 20 AND seed % 7 = 0 THEN 'Scheduled' ELSE 'Paid' END,
    1 + (seed % 55) / 10.0, CASE WHEN seed % 10 < 8 THEN 1 ELSE 0 END,
    CONVERT(date,'20261009',112)
FROM samples OPTION (MAXRECURSION 180);
