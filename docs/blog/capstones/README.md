# Capstone walkthroughs

Long-form "how it was built" articles for two of the capstone apps, salvaged from
the old standalone capstone repo. Styled standalone HTML — open them in a browser.

- `support-desk-walkthrough.html` — *Building a Production AI Support Assistant with CafeAI*
- `meridian-qualify-walkthrough.html` — *Building a Regulated AI Loan Pre-Qualification Assistant with CafeAI*

**Dated (written against CafeAI ~0.1.1).** The narrative holds; some code does not:
`app.tool(...)` is now `app.agent(...).tool(...)`, OTel span attributes moved to
`gen_ai.*`, and the apps now live in `capstones/` consuming `project(':cafeai-*')`.
The polished, current version of this material is `docs/blog/12-the-capstone-series.md`
and each app's own `capstones/*/README.md`.
