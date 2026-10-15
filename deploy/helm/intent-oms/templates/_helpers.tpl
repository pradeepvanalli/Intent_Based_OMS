{{/* Chart helpers */}}
{{- define "intent-oms.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "intent-oms.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "intent-oms.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "intent-oms.labels" -}}
helm.sh/chart: {{ include "intent-oms.chart" . }}
{{ include "intent-oms.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: intent-oms
{{- end }}

{{- define "intent-oms.selectorLabels" -}}
app.kubernetes.io/name: {{ include "intent-oms.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "intent-oms.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "intent-oms.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "intent-oms.image" -}}
{{- if .Values.image.digest }}
{{- printf "%s@%s" .Values.image.repository .Values.image.digest }}
{{- else }}
{{- printf "%s:%s" .Values.image.repository (.Values.image.tag | default .Chart.AppVersion) }}
{{- end }}
{{- end }}

{{- define "intent-oms.secretName" -}}
{{- if .Values.secrets.existingSecret }}{{ .Values.secrets.existingSecret }}{{ else }}{{ include "intent-oms.fullname" . }}{{ end }}
{{- end }}

{{- define "intent-oms.configMapName" -}}
{{ include "intent-oms.fullname" . }}-env
{{- end }}

{{- define "intent-oms.configDirName" -}}
{{- if .Values.configDir.existingConfigMap }}{{ .Values.configDir.existingConfigMap }}{{ else }}{{ include "intent-oms.fullname" . }}-config-dir{{ end }}
{{- end }}

{{- define "intent-oms.postgresql.fullname" -}}
{{ include "intent-oms.fullname" . }}-postgresql
{{- end }}

{{- define "intent-oms.postgresql.secretName" -}}
{{- if .Values.postgresql.existingSecret }}{{ .Values.postgresql.existingSecret }}{{ else }}{{ include "intent-oms.postgresql.fullname" . }}{{ end }}
{{- end }}

{{/* Effective Spring profile: explicit value wins; otherwise prod unless demo mode. */}}
{{- define "intent-oms.springProfile" -}}
{{- if .Values.springProfile }}{{ .Values.springProfile }}{{ else if .Values.demo.enabled }}{{ "" }}{{ else }}prod{{ end }}
{{- end }}

{{/* Keep a generated secret value across upgrades: explicit value > existing secret > random. */}}
{{- define "intent-oms.secretValue" -}}
{{- $ctx := index . 0 }}{{- $key := index . 1 }}{{- $length := index . 2 }}
{{- $explicit := index $ctx.Values.secrets.values $key }}
{{- if $explicit }}{{ $explicit }}
{{- else }}
{{- $existing := (lookup "v1" "Secret" $ctx.Release.Namespace (include "intent-oms.fullname" $ctx)) }}
{{- if and $existing $existing.data (index $existing.data $key) }}{{ index $existing.data $key | b64dec }}
{{- else }}{{ randAlphaNum $length }}{{ end }}
{{- end }}
{{- end }}
