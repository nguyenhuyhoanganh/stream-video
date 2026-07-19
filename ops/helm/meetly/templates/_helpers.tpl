{{- define "meetly.apiImage" -}}
{{ .Values.image.registry }}/{{ .Values.image.apiRepository }}:{{ .Values.image.tag }}
{{- end -}}
{{- define "meetly.webImage" -}}
{{ .Values.image.registry }}/{{ .Values.image.webRepository }}:{{ .Values.image.tag }}
{{- end -}}
