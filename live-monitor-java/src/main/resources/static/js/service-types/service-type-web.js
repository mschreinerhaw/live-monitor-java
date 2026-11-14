function normalizeWebUrl(value, scheme) {
  const url = String(value || "").trim();
  if (!url) return "";
  if (/^https?:\/\//i.test(url)) return url;
  return `${scheme === "http" ? "http" : "https"}://${url}`;
}

function webSchemeFromUrl(value) {
  return /^http:\/\//i.test(value || "") ? "http" : "https";
}

function normalizeWebUrlInput(form) {
  const urlInput = form.elements.url;
  if (!urlInput || !urlInput.value.trim()) return;
  urlInput.value = normalizeWebUrl(urlInput.value, form.elements.web_scheme?.value);
}
