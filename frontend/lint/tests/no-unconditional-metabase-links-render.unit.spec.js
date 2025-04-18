import { RuleTester } from "eslint";

import noUnconditionalMetabaseLinksRender from "../eslint-rules/no-unconditional-metabase-links-render";

const ruleTester = new RuleTester({
  parserOptions: {
    ecmaVersion: 2015,
    sourceType: "module",
    ecmaFeatures: { jsx: true },
  },
});

// Only importing `getShowMetabaseLinks` is not enough to pass the rule.
// This doesn't necessarily mean that the link would be definitely conditionally
// rendered using the result of this selector, but it gives us enough confidence
// that it would be used. And we trust engineers that they would use it correctly.
// We could go extra miles and add that check, but I'd want to leave it as is
// for simplicity.
const VALID_CASES = [
  {
    code: `
import { useSelector } from "metabase/lib/redux";
import { getDocsUrl } from "metabase/selectors/settings";
import { getShowMetabaseLinks } from "metabase/selectors/whitelabel";

const docsLink = useSelector(state =>
  getDocsUrl(state, { page: "actions/custom" })
);`,
  },
  {
    code: `
import { useSelector } from "metabase/lib/redux";
import { getLearnUrl } from "metabase/selectors/settings";
import { getShowMetabaseLinks } from "metabase/selectors/whitelabel";

const docsLink = useSelector(state =>
  getLearnUrl(state, { page: "actions/custom" })
);`,
  },
  {
    code: `
import { getShowMetabaseLinks } from "metabase/selectors/whitelabel";

function MyComponent() {
  return <a href="https://metabase.com/docs/latest/troubleshooting-guide/bugs.html">Troubleshooting</a>;
}`,
  },
  {
    code: `
import { getShowMetabaseLinks } from "metabase/selectors/whitelabel";

function MyComponent() {
  return <a href={\`https://metabase.com/docs/latest/troubleshooting-guide/bugs.html\`}>Troubleshooting</a>;
}`,
  },
  {
    code: `
import { getShowMetabaseLinks } from "metabase/selectors/whitelabel";

function MyComponent() {
  return <a href="https://www.metabase.com/learn/getting-started/">Getting started</a>;
}`,
  },
  {
    code: `
function MyComponent() {
  // eslint-disable-next-line no-unconditional-metabase-links-render -- Only shows for admins.
  return <a href="https://www.metabase.com/learn/getting-started/">Getting started</a>;
}`,
  },
  {
    code: `
const { url, showMetabaseLinks } = useDocsUrl("permissions/data");`,
  },
  {
    code: `
const { url: docsUrl, showMetabaseLinks: shouldShowLink } = useDocsUrl("permissions/data");`,
  },
  {
    code: `
// eslint-disable-next-line no-unconditional-metabase-links-render -- Only shows for admins.
const { url } = useDocsUrl("permissions/data");`,
  },
];
const INVALID_CASES = [
  {
    name: "Detect getDocsUrl()",
    code: `
import { useSelector } from "metabase/lib/redux";
import { getDocsUrl } from "metabase/selectors/settings";

const docsLink = useSelector(state =>
  getDocsUrl(state, { page: "actions/custom" })
);`,
    error:
      /Metabase links must be rendered conditionally\.(.|\n)*Please import `getShowMetabaseLinks`(.|\n)*Or add comment to indicate the reason why this rule needs to be disabled/,
  },
  {
    name: "Detect getLearnUrl()",
    code: `
import { useSelector } from "metabase/lib/redux";
import { getLearnUrl } from "metabase/selectors/settings";

const docsLink = useSelector(state =>
  getLearnUrl(state, { page: "actions/custom" })
);`,
    error:
      /Metabase links must be rendered conditionally\.(.|\n)*Please import `getShowMetabaseLinks`(.|\n)*Or add comment to indicate the reason why this rule needs to be disabled/,
  },
  {
    name: 'Detect "metabase.com/docs"',
    code: `
function MyComponent() {
  return <a href="https://metabase.com/docs/latest/troubleshooting-guide/bugs.html">Troubleshooting</a>;
}`,
    error:
      /Metabase links must be rendered conditionally\.(.|\n)*Please import `getShowMetabaseLinks`(.|\n)*Or add comment to indicate the reason why this rule needs to be disabled/,
  },
  {
    name: 'Detect "metabase.com/docs" in template strings',
    code: `
function MyComponent() {
  return <a href={\`https://metabase.com/docs/latest/troubleshooting-guide/bugs.html\`}>Troubleshooting</a>;
}`,
    error:
      /Metabase links must be rendered conditionally\.(.|\n)*Please import `getShowMetabaseLinks`(.|\n)*Or add comment to indicate the reason why this rule needs to be disabled/,
  },
  {
    name: 'Detect "metabase.com/learn"',
    code: `
function MyComponent() {
  return <a href="https://www.metabase.com/learn/getting-started/">Getting started</a>;
}`,
    error:
      /Metabase links must be rendered conditionally\.(.|\n)*Please import `getShowMetabaseLinks`(.|\n)*Or add comment to indicate the reason why this rule needs to be disabled/,
  },
  {
    name: 'Detect "metabase.com/learn" in template strings',
    code: `
function MyComponent() {
  return <a href={\`https://www.metabase.com/learn/getting-started/\`}>Getting started</a>;
}`,
    error:
      /Metabase links must be rendered conditionally\.(.|\n)*Please import `getShowMetabaseLinks`(.|\n)*Or add comment to indicate the reason why this rule needs to be disabled/,
  },
  {
    name: "Detect disabled rule next line",
    code: `
function MyComponent() {
  // eslint-disable-next-line no-unconditional-metabase-links-render
  return <a href="https://www.metabase.com/learn/getting-started/">Getting started</a>;
}`,
    error:
      /Please add comment to indicate the reason why this rule needs to be disabled./,
  },
  {
    name: "Detect disabled rule block",
    code: `
/* eslint-disable no-unconditional-metabase-links-render */

function MyComponent() {
  return <a href="https://www.metabase.com/learn/getting-started/">Getting started</a>;
}`,
    error: "Please use inline disable with comments instead.",
  },
  {
    code: `
function MyComponent() {
  const { url } = useDocsUrl("permissions/data");
  let foo="bar";
}`,
    error: /Metabase links must be rendered conditionally/,
  },
  {
    code: `
const { url: docsUrl } = useDocsUrl("permissions/data");`,
    error: /Metabase links must be rendered conditionally/,
  },
  {
    code: `
// eslint-disable-next-line no-unconditional-metabase-links-render
const { url } = useDocsUrl("permissions/data");`,
    error:
      /Please add comment to indicate the reason why this rule needs to be disabled./,
  },
];

ruleTester.run(
  "no-unconditional-metabase-links-render",
  noUnconditionalMetabaseLinksRender,
  {
    valid: VALID_CASES,
    invalid: INVALID_CASES.map((invalidCase) => {
      return {
        code: invalidCase.code,
        errors: [
          {
            message: invalidCase.error,
          },
        ],
      };
    }),
  },
);
