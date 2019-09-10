.PHONE: all
all: build push deploy

.PHONY: build
build: 
	docker build . -t gcr.io/zeebe-io/slack-app:latest

.PHONY: push
push: 
	docker push gcr.io/zeebe-io/slack-app:latest

.PHONY: deploy
deploy:
	kubectl delete -f k8s/slack-app.yml
	kubectl apply -f k8s/slack-app.yml
